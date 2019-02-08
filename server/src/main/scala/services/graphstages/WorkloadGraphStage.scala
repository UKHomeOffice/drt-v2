package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.Map
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
    var loadMinutes: Map[TQM, LoadMinute] = Map()
    var flightTQMs: Map[Int, List[TQM]] = Map()
    var flightLoadMinutes: Map[TQM, Set[FlightSplitMinute]] = Map()
    var updatedLoadsToPush: Map[TQM, LoadMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      loadMinutes = optionalInitialLoads match {
        case Some(Loads(lms)) =>
          log.info(s"Received ${lms.size} initial loads")
          val byId = lms
            .map(lm => (lm.uniqueId, lm))
            .toMap
          val afterPurged = purgeExpired(byId, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)
          log.info(s"Storing ${afterPurged.size} initial loads")
          afterPurged
        case _ =>
          log.warn(s"Did not receive any loads to initialise with")
          Map()
      }
      flightLoadMinutes = optionalInitialFlightsWithSplits match {
        case Some(fws: FlightsWithSplits) =>
          log.info(s"Received ${fws.flights.size} initial flights. Calculating workload.")
          val timeAccessor = (fsms: Set[FlightSplitMinute]) => if (fsms.nonEmpty) fsms.map(_.minute).min else 0L
          val updatedWorkloads: Map[TQM, Set[FlightSplitMinute]] = initialiseFlightLoadMinutes(fws)
          purgeExpired(updatedWorkloads, timeAccessor, now, expireAfterMillis)
        case None =>
          log.warn(s"Didn't receive any initial flights to initialise with")
          Map()
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
        log.info(s"Received ${incomingFlights.flights.size} arrivals")

        val existingFlightTQMs: Set[TQM] = incomingFlights.flights.flatMap(fws => flightTQMs.getOrElse(fws.apiFlight.uniqueId, List())).toSet

        val updatedWorkloads: Map[TQM, Set[FlightSplitMinute]] = initialiseFlightLoadMinutes(incomingFlights)

        val affectedTQMs = updatedWorkloads.keys.toSet ++ existingFlightTQMs

        val flmsMinusExisting = existingFlightTQMs.foldLeft(flightLoadMinutes) {
          case (soFar, tqm) => soFar.updated(tqm, soFar.getOrElse(tqm, Set()).filterNot(fsm => incomingFlights.flights.map(_.apiFlight.uniqueId).contains(fsm.flightId)))
        }
        val updatedFlightLoadMinutes = updatedWorkloads.foldLeft(flmsMinusExisting) {
          case (soFar, (tqm, newLm)) => soFar.updated(tqm, soFar.getOrElse(tqm, Set()) ++ newLm)
        }
        flightLoadMinutes = updatedFlightLoadMinutes //purgeExpired(updatedFlightLoadMinutes, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        val affectedLoads = flightSplitMinutesToQueueLoadMinutes(affectedTQMs)
        val latestDiff = affectedLoads.foldLeft(Map[TQM, LoadMinute]()) {
          case (soFar, (tqm, lm)) => loadMinutes.get(tqm) match {
            case Some(existingLm) if existingLm == lm => soFar
            case _ => soFar.updated(tqm, lm)
          }
        }
        val updatedLoads = latestDiff.foldLeft(loadMinutes) {
          case (soFar, (tqm, newLm)) => soFar.updated(tqm, newLm)
        }


        loadMinutes = purgeExpired(updatedLoads, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        updatedLoadsToPush = purgeExpired(mergeLoadMinutes(latestDiff, updatedLoadsToPush), (lm: LoadMinute) => lm.minute, now, expireAfterMillis)
        log.info(s"${updatedLoadsToPush.size} load minutes to push (${updatedLoadsToPush.values.count(_.paxLoad == 0d)} zero pax minutes)")

        pushStateIfReady()

        pullFlights()
        log.info(s"inFlightsWithSplits Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
      }
    })

    def mergeLoadMinutes(updatedLoads: Map[TQM, LoadMinute], existingLoads: Map[TQM, LoadMinute]): Map[TQM, LoadMinute] = updatedLoads.foldLeft(existingLoads) {
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

    def initialiseFlightLoadMinutes(incomingFlights: FlightsWithSplits): Map[TQM, Set[FlightSplitMinute]] = incomingFlights
      .flights
      .filterNot(f => {
        val cancelled = f.apiFlight.Status == "Cancelled"
        if (cancelled) log.info(s"No workload for cancelled flight ${f.apiFlight.IATA}")
        cancelled
      })
      .foldLeft(Map[TQM, Set[FlightSplitMinute]]()) {
        case (soFar, fws) =>
          airportConfig.defaultProcessingTimes.get(fws.apiFlight.Terminal) match {
            case None =>
              log.warn(s"No proc times found for ${fws.apiFlight.IATA} @ ${fws.apiFlight.Terminal}. Can't calculate workload")
              soFar
            case Some(procTimes) =>
              val flightWorkload: Set[FlightSplitMinute] = WorkloadCalculator.flightToFlightSplitMinutes(fws, procTimes, natProcTimes, useNationalityBasedProcessingTimes)
              val tqms = flightWorkload.map(f => TQM(f.terminalName, f.queueName, f.minute)).toList
              flightTQMs = flightTQMs.updated(fws.apiFlight.uniqueId, tqms)
              flightWorkload.foldLeft(soFar) {
                case (soFarSoFar, fsm) =>
                  val tqm = TQM(fsm.terminalName, fsm.queueName, fsm.minute)
                  soFarSoFar.updated(tqm, soFarSoFar.getOrElse(tqm, Set[FlightSplitMinute]()) + fsm)
              }
          }
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
        push(outLoads, Loads(updatedLoadsToPush.values.toSet))
        updatedLoadsToPush = Map()
      }
      else log.info(s"outLoads not available to push")
    }

    def flightSplitMinutesToQueueLoadMinutes(flightToFlightSplitMinutes: Map[Int, Set[FlightSplitMinute]]): Map[TQM, LoadMinute] = {
      flightToFlightSplitMinutes
        .values
        .flatten
        .groupBy(s => {
          val finalQueueName = airportConfig.divertedQueues.getOrElse(s.queueName, s.queueName)
          (s.terminalName, finalQueueName, s.minute)
        })
        .map {
          case ((terminalName, queueName, minute), fsms) =>
            val paxLoad = fsms.map(_.paxLoad).sum
            val workLoad = fsms.map(_.workLoad).sum
            val loadMinute = LoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
            (loadMinute.uniqueId, loadMinute)
        }
    }

    def flightSplitMinutesToQueueLoadMinutes_(tqms: Set[TQM]): Map[TQM, LoadMinute] = tqms.map(tqm => {
      val fqms = flightLoadMinutes.getOrElse(tqm, Set())
      val paxLoad = fqms.toSeq.map(_.paxLoad).sum
      val workLoad = fqms.toSeq.map(_.workLoad).sum
      val loadMinute = LoadMinute(tqm.terminalName, tqm.queueName, paxLoad, workLoad, tqm.minute)
      (tqm, loadMinute)
    }).toMap

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
}

