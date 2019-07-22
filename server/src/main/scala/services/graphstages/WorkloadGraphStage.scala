package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.{Map, SortedMap}
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
    var flightTQMs: Map[Int, List[TQM]] = Map()
    var flightLoadMinutes: SortedMap[TQM, Set[FlightSplitMinute]] = SortedMap()
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
          val (updatedWorkloads, flightTqms) = affectedWorkloadsAndTqms(fws)
          flightLoadMinutes = purgeExpired(updatedWorkloads, now, expireAfterMillis.toInt)
          flightTQMs = flightTqms
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

        val incomingFlightsTqms: Set[TQM] = incomingFlights.flightsToUpdate.flatMap(fws => flightTQMs.getOrElse(fws.apiFlight.uniqueId, List())).toSet
        log.info(s"Got existing flight TQMs")
        val (incomingWorkloads, incomingTqmsByFlight) = affectedWorkloadsAndTqms(incomingFlights)
        flightTQMs = flightTQMs ++ incomingTqmsByFlight
        log.info(s"Got updated workloads")

        val newAffectedTqmSplits = affectedTqmSplitsFromIncoming(incomingFlightsTqms, incomingWorkloads, incomingFlights)
        flightLoadMinutes = flightLoadMinutes ++ newAffectedTqmSplits
        log.info(s"Merged updated workloads into existing")

        val affectedTqms = newAffectedTqmSplits.keys
        log.info(s"Got affected TQMs")
        val latestDiff = diffFromTQMs(affectedTqms)
        log.info(s"Got latestDiff")

        loadMinutes = loadMinutes ++ latestDiff
        log.info(s"Merged load minutes")
        updatedLoadsToPush = purgeExpired(updatedLoadsToPush ++ latestDiff, now, expireAfterMillis.toInt)
        log.info(s"${updatedLoadsToPush.size} load minutes to push (${updatedLoadsToPush.values.count(_.paxLoad == 0d)} zero pax minutes)")

        pushStateIfReady()

        pullFlights()
        log.info(s"inFlightsWithSplits Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def diffFromTQMs(affectedTQMs: Iterable[TQM]): List[(TQM, LoadMinute)] = {
      val affectedLoads = flightSplitMinutesToQueueLoadMinutes(affectedTQMs)
      affectedLoads.foldLeft(List[(TQM, LoadMinute)]()) {
        case (soFar, (tqm, lm)) => loadMinutes.get(tqm) match {
          case Some(existingLm) if existingLm == lm => soFar
          case _ => (tqm, lm) :: soFar
        }
      }
    }

    def affectedTqmSplitsFromIncoming(incomingFlightsOldTqms: Set[TQM], incomingWorkloads: SortedMap[TQM, Set[FlightSplitMinute]], incomingFlights: FlightsWithSplits): SortedMap[TQM, Set[FlightSplitMinute]] = {
      val arrivalIds: Set[Int] = incomingFlights.flightsToUpdate.map(_.apiFlight.uniqueId).toSet

      val oldSplitMinutesRemoved = SortedMap[TQM, Set[FlightSplitMinute]]() ++ incomingFlightsOldTqms.foldLeft(List[(TQM, Set[FlightSplitMinute])]()) {
        case (affectedSoFar, tqm) =>
          val existingFlightSplitsMinutes: Set[FlightSplitMinute] = flightLoadMinutes.getOrElse(tqm, Set[FlightSplitMinute]())
          val minusIncomingSplitMinutes = existingFlightSplitsMinutes.filterNot(fsm => arrivalIds.contains(fsm.flightId))
          (tqm, minusIncomingSplitMinutes) :: affectedSoFar
      }

      val allAffectedSplitMinutes = incomingWorkloads.foldLeft(oldSplitMinutesRemoved) {
        case (soFar, (tqm, newLm)) =>
          val splitMinutes = soFar.getOrElse(tqm, flightLoadMinutes.getOrElse(tqm, Set()))
          soFar.updated(tqm, splitMinutes ++ newLm)
      }

      allAffectedSplitMinutes
    }

    def loadDiff(updatedLoads: Map[TQM, LoadMinute], existingLoads: Map[TQM, LoadMinute]): Map[TQM, LoadMinute] = {
      val updates: List[(TQM, LoadMinute)] = updatedLoads.foldLeft(List[(TQM, LoadMinute)]()) {
        case (soFar, (key, updatedLoad)) =>
          existingLoads.get(key) match {
            case Some(existingLoadMinute) if existingLoadMinute == updatedLoad => soFar
            case _ => (key, updatedLoad) :: soFar
          }
      }
      val toRemoveIds = existingLoads.keys.toSet -- updatedLoads.keys.toSet
      val removes = toRemoveIds
        .map(id => existingLoads.get(id))
        .collect { case Some(lm) if lm.workLoad != 0 => (lm.uniqueId, lm.copy(paxLoad = 0, workLoad = 0)) }

      val diff = updates.toMap ++ removes
      log.info(s"${diff.size} updated load minutes (${updates.size} updates + ${removes.size} removes)")

      diff
    }

    def affectedWorkloadsAndTqms(incomingFlights: FlightsWithSplits): (SortedMap[TQM, Set[FlightSplitMinute]], Map[Int, List[TQM]]) = incomingFlights
      .flightsToUpdate
      .filterNot(isCancelled)
      .filter(hasProcessingTime)
      .foldLeft(SortedMap[TQM, Set[FlightSplitMinute]](), Map[Int, List[TQM]]()) {
        case ((flightWorkloadsSoFar, flightTqmsSoFar), fws) =>
          airportConfig.defaultProcessingTimes.get(fws.apiFlight.Terminal)
            .map(procTimes => {
              val flightWorkload = WorkloadCalculator.flightToFlightSplitMinutes(fws, procTimes, natProcTimes, useNationalityBasedProcessingTimes)
              val flightTqms = updateTqmsForFlight(fws, flightWorkload)
              val splitMinutes = mergeWorkloadsFromFlight(flightWorkloadsSoFar, flightWorkload)
              (splitMinutes, flightTqmsSoFar.updated(fws.apiFlight.uniqueId, flightTqms))
            })
            .getOrElse((flightWorkloadsSoFar, flightTqmsSoFar))
      }

    def mergeWorkloadsFromFlight(existingFlightSplitMinutes: SortedMap[TQM, Set[FlightSplitMinute]], flightWorkload: Set[FlightSplitMinute]): SortedMap[TQM, Set[FlightSplitMinute]] =
      flightWorkload.foldLeft(existingFlightSplitMinutes) {
        case (soFarSoFar, fsm) =>
          val tqm = TQM(fsm.terminalName, fsm.queueName, fsm.minute)
          soFarSoFar.updated(tqm, soFarSoFar.getOrElse(tqm, Set[FlightSplitMinute]()) + fsm)
      }

    def updateTqmsForFlight(fws: ApiFlightWithSplits, flightWorkload: Set[FlightSplitMinute]): List[TQM] =
      flightWorkload.map(f => TQM(f.terminalName, f.queueName, f.minute)).toList

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

    def flightSplitMinutesToQueueLoadMinutes(tqms: Iterable[TQM]): Map[TQM, LoadMinute] = tqms
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

