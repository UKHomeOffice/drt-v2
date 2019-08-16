package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{Map, SortedMap}
import scala.collection.mutable
import scala.language.postfixOps


class WorkloadGraphStage(name: String = "",
                         optionalInitialLoads: Option[Loads],
                         optionalInitialFlightsWithSplits: Option[FlightsWithSplits],
                         airportConfig: AirportConfig,
                         natProcTimes: Map[String, Double],
                         expireAfterMillis: Long,
                         now: () => SDateLike,
                         useNationalityBasedProcessingTimes: Boolean)
  extends GraphStage[FlowShape[FlightsWithSplits, Loads]] {

  val inFlightsWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("inFlightsWithSplits.in")
  val outLoads: Outlet[Loads] = Outlet[Loads]("PortStateOut.out")

  val paxDisembarkPerMinute = 20

  override val shape = new FlowShape(inFlightsWithSplits, outLoads)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutes: SortedMap[TQM, LoadMinute] = SortedMap()
    val flightTQMs: mutable.Map[Int, List[TQM]] = mutable.Map()
    val flightLoadMinutes: mutable.SortedMap[TQM, Set[FlightSplitMinute]] = mutable.SortedMap()
    var updatedLoadsToPush: SortedMap[TQM, LoadMinute] = SortedMap()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      loadMinutes = optionalInitialLoads match {
        case Some(Loads(lms)) =>
          log.info(s"Received ${lms.size} initial loads")
          val afterPurged = purgeExpired(lms, now, expireAfterMillis.toInt)
          log.info(s"Storing ${afterPurged.size} initial loads")
          afterPurged
        case _ =>
          log.warn(s"Did not receive any loads to initialise with")
          SortedMap()
      }
      optionalInitialFlightsWithSplits match {
        case Some(fws: FlightsWithSplits) =>
          log.info(s"Received ${fws.flightsToUpdate.size} initial flights. Calculating workload.")
          val updatedWorkloads = flightLoadMinutes(fws)
          purgeExpired(updatedWorkloads, now, expireAfterMillis.toInt)
        case None =>
          log.warn(s"Didn't receive any initial flights to initialise with")
      }

      super.preStart()
    }

    def flightsToWorkloadByFlightId(initialFlights: Seq[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
      initialFlights
        .map(fws => {
          val uniqueFlightId = fws.apiFlight.uniqueId
          val flightWorkload = WorkloadCalculator.flightToFlightSplitMinutes(fws, airportConfig.defaultProcessingTimes.head._2, natProcTimes, useNationalityBasedProcessingTimes)

          (uniqueFlightId, flightWorkload)
        })
        .toMap
    }

    setHandler(inFlightsWithSplits, new InHandler {
      override def onPush(): Unit = {
        val start = SDate.now()
        val incomingFlights = grab(inFlightsWithSplits)
        log.info(s"Received ${incomingFlights.flightsToUpdate.size} arrivals")

        val existingFlightTQMs: Set[TQM] = incomingFlights.flightsToUpdate.flatMap(fws => flightTQMs.getOrElse(fws.apiFlight.uniqueId, List())).toSet
        log.info(s"Got existing flight TQMs")
        val updatedWorkloads = flightLoadMinutes(incomingFlights)

        log.info(s"Got updated workloads")

        mergeUpdatedFlightLoadMinutes(existingFlightTQMs, updatedWorkloads, incomingFlights)
        log.info(s"Merged updated workloads into existing")

        val affectedTQMs = updatedWorkloads.keys.toSet ++ existingFlightTQMs
        log.info(s"Got affected TQMs")
        val latestDiff = diffFromTQMs(affectedTQMs)
        log.info(s"Got latestDiff")

        loadMinutes = mergeLoadMinutes(latestDiff, loadMinutes)
        log.info(s"Merged load minutes")
        updatedLoadsToPush = purgeExpired(mergeLoadMinutes(latestDiff, updatedLoadsToPush), now, expireAfterMillis.toInt)
        log.info(s"${updatedLoadsToPush.size} load minutes to push (${updatedLoadsToPush.values.count(_.paxLoad == 0d)} zero pax minutes)")

        pushStateIfReady()

        pullFlights()
        log.info(s"inFlightsWithSplits Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def diffFromTQMs(affectedTQMs: Set[TQM]): Map[TQM, LoadMinute] = {
      val affectedLoads = flightSplitMinutesToQueueLoadMinutes(affectedTQMs)
      affectedLoads.foldLeft(Map[TQM, LoadMinute]()) {
        case (soFar, (tqm, lm)) => loadMinutes.get(tqm) match {
          case Some(existingLm) if existingLm == lm => soFar
          case _ => soFar.updated(tqm, lm)
        }
      }
    }

    def mergeUpdatedFlightLoadMinutes(existingFlightTQMs: Set[TQM], updatedWorkloads: mutable.SortedMap[TQM, Set[FlightSplitMinute]], incomingFlights: FlightsWithSplits): Unit = {
      val arrivalIds: Set[Int] = incomingFlights.flightsToUpdate.map(_.apiFlight.uniqueId).toSet
      existingFlightTQMs.foreach { tqm =>
        val existingFlightSplitsMinutes: Set[FlightSplitMinute] = flightLoadMinutes.getOrElse(tqm, Set[FlightSplitMinute]())
        val minusIncomingSplitMinutes = existingFlightSplitsMinutes.filterNot(fsm => arrivalIds.contains(fsm.flightId))
        flightLoadMinutes += (tqm -> minusIncomingSplitMinutes)
      }
      updatedWorkloads.foreach {
        case (tqm, newLm) =>
          val newLoadMinutes = flightLoadMinutes.getOrElse(tqm, Set()) ++ newLm
          flightLoadMinutes += (tqm -> newLoadMinutes)
      }
      purgeExpired(flightLoadMinutes, now, expireAfterMillis.toInt)
    }

    def mergeLoadMinutes(updatedLoads: Map[TQM, LoadMinute], existingLoads: SortedMap[TQM, LoadMinute]): SortedMap[TQM, LoadMinute] = updatedLoads.foldLeft(existingLoads) {
      case (soFar, (key, newLoadMinute)) => soFar.updated(key, newLoadMinute)
    }

    def loadDiff(updatedLoads: Map[TQM, LoadMinute], existingLoads: Map[TQM, LoadMinute]): Map[TQM, LoadMinute] = {
      val updates: Map[TQM, LoadMinute] = updatedLoads.foldLeft(Map[TQM, LoadMinute]()) {
        case (soFar, (key, updatedLoad)) =>
          existingLoads.get(key) match {
            case Some(existingLoadMinute) if existingLoadMinute == updatedLoad => soFar
            case _ => soFar.updated(key, updatedLoad)
          }
      }
      val toRemoveIds = existingLoads.keys.toSet -- updatedLoads.keys.toSet
      val removes = toRemoveIds
        .map(id => existingLoads.get(id))
        .collect { case Some(lm) if lm.workLoad != 0 => (lm.uniqueId, lm.copy(paxLoad = 0, workLoad = 0)) }

      val diff = updates ++ removes
      log.info(s"${diff.size} updated load minutes (${updates.size} updates + ${removes.size} removes)")

      diff
    }

    def flightLoadMinutes(incomingFlights: FlightsWithSplits): mutable.SortedMap[TQM, Set[FlightSplitMinute]] = {
      val flightWorkloadsSoFar = mutable.SortedMap[TQM, Set[FlightSplitMinute]]()
      incomingFlights
        .flightsToUpdate
        .filterNot(isCancelled)
        .filter(hasProcessingTime)
        .foreach { fws =>
          airportConfig.defaultProcessingTimes.get(fws.apiFlight.Terminal)
            .foreach(procTimes => {
              val flightWorkload = WorkloadCalculator.flightToFlightSplitMinutes(fws, procTimes, natProcTimes, useNationalityBasedProcessingTimes)
              updateTQMsForFlight(fws, flightWorkload)
              mergeWorkloadsFromFlight(flightWorkloadsSoFar, flightWorkload)
            })
        }
      flightWorkloadsSoFar
    }

    def mergeWorkloadsFromFlight(existingFlightSplitMinutes: SortedMap[TQM, Set[FlightSplitMinute]], flightWorkload: Set[FlightSplitMinute]): SortedMap[TQM, Set[FlightSplitMinute]] =
      flightWorkload.foldLeft(existingFlightSplitMinutes) {
        case (soFarSoFar, fsm) =>
          val tqm = TQM(fsm.terminalName, fsm.queueName, fsm.minute)
          soFarSoFar.updated(tqm, soFarSoFar.getOrElse(tqm, Set[FlightSplitMinute]()) + fsm)
      }

    def mergeWorkloadsFromFlight(existingFlightSplitMinutes: mutable.SortedMap[TQM, Set[FlightSplitMinute]], flightWorkload: Set[FlightSplitMinute]): Unit =
      flightWorkload.foreach { fsm =>
        val tqm = TQM(fsm.terminalName, fsm.queueName, fsm.minute)
        val newFlightSplitsMinutes = existingFlightSplitMinutes.getOrElse(tqm, Set[FlightSplitMinute]()) + fsm
        existingFlightSplitMinutes += (tqm -> newFlightSplitsMinutes)
      }

    def updateTQMsForFlight(fws: ApiFlightWithSplits, flightWorkload: Set[FlightSplitMinute]): Unit = {
      val tqms = flightWorkload.map(f => TQM(f.terminalName, f.queueName, f.minute)).toList
      flightTQMs += (fws.apiFlight.uniqueId -> tqms)
    }

    setHandler(outLoads, new OutHandler {
      override def onPull(): Unit = {
        val start = SDate.now()
        log.debug(s"outLoads onPull called")
        pushStateIfReady()
        pullFlights()
        log.info(s"outLoads Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def pullFlights(): Unit = {
      if (!hasBeenPulled(inFlightsWithSplits)) {
        log.info(s"Pulling inFlightsWithSplits")
        pull(inFlightsWithSplits)
      }
    }

    def pushStateIfReady(): Unit = {
      if (updatedLoadsToPush.isEmpty)
        log.info(s"We have no load minutes. Nothing to push")
      else if (isAvailable(outLoads)) {
        log.info(s"Pushing ${updatedLoadsToPush.size} load minutes")
        push(outLoads, Loads(updatedLoadsToPush))
        updatedLoadsToPush = SortedMap()
      }
      else log.info(s"outLoads not available to push")
    }

    def flightSplitMinutesToQueueLoadMinutes(tqms: Set[TQM]): Map[TQM, LoadMinute] = tqms
      .map(tqm => {
        val fqms = flightLoadMinutes.getOrElse(tqm, Set())
        val paxLoad = fqms.toSeq.map(_.paxLoad).sum
        val workLoad = fqms.toSeq.map(_.workLoad).sum
        LoadMinute(tqm.terminalName, tqm.queueName, paxLoad, workLoad, tqm.minute)
      })
      .groupBy(s => {
        val finalQueueName = airportConfig.divertedQueues.getOrElse(s.queueName, s.queueName)
        TQM(s.terminalName, finalQueueName, s.minute)
      })
      .map {
        case (tqm, lms) => (tqm, LoadMinute(tqm.terminalName, tqm.queueName, lms.toSeq.map(_.paxLoad).sum, lms.toSeq.map(_.workLoad).sum, tqm.minute))
      }
  }

  def isCancelled(f: ApiFlightWithSplits): Boolean = {
    val cancelled = f.apiFlight.Status == "Cancelled"
    if (cancelled) log.info(s"No workload for cancelled flight ${f.apiFlight.IATA}")
    cancelled
  }

  def hasProcessingTime(f: ApiFlightWithSplits): Boolean = {
    val timeExists = airportConfig.defaultProcessingTimes.contains(f.apiFlight.Terminal)
    if (!timeExists) log.info(s"No processing times for ${f.apiFlight.IATA} as terminal ${f.apiFlight.Terminal}")
    timeExists
  }
}

