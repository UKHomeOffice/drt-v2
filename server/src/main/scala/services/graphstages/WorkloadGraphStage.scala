package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.metrics.{Metrics, StageTimer}

import scala.collection.immutable.{Map, SortedMap}
import scala.collection.mutable


class WorkloadGraphStage(name: String = "",
                         optionalInitialLoads: Option[Loads],
                         optionalInitialFlightsWithSplits: Option[FlightsWithSplits],
                         airportConfig: AirportConfig,
                         natProcTimes: Map[String, Double],
                         expireAfterMillis: Long,
                         now: () => SDateLike,
                         useNationalityBasedProcessingTimes: Boolean)
  extends GraphStage[FlowShape[FlightsWithSplits, Loads]] {

  val inFlightsWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("FlightsWithSplits.in")
  val outLoads: Outlet[Loads] = Outlet[Loads]("Loads.out")
  val stageName = "workload"

  val paxDisembarkPerMinute = 20

  override val shape = new FlowShape(inFlightsWithSplits, outLoads)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val loadMinutes: mutable.SortedMap[TQM, LoadMinute] = mutable.SortedMap()
    val flightTQMs: mutable.SortedMap[CodeShareKeyOrderedBySchedule, List[TQM]] = mutable.SortedMap()
    val flightLoadMinutes: mutable.SortedMap[TQM, Set[FlightSplitMinute]] = mutable.SortedMap()
    val updatedLoadsToPush: mutable.SortedMap[TQM, LoadMinute] = mutable.SortedMap()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    val daysAffectedByArrival: Arrival => Set[String] = Crunch.arrivalDaysAffected(airportConfig.crunchOffsetMinutes, Crunch.paxOffPerMinute)
    val daysAffectedByTqms: List[TQM] => Set[String] = Crunch.tqmsDaysAffected(airportConfig.crunchOffsetMinutes, Crunch.paxOffPerMinute)

    override def preStart(): Unit = {
      optionalInitialLoads.foreach { case Loads(lms) =>
        log.info(s"Received ${lms.size} initial loads")
        loadMinutes ++= lms
        purgeExpired(loadMinutes, TQM.atTime, now, expireAfterMillis.toInt)
        log.info(s"Storing ${loadMinutes.size} initial loads")
      }
      optionalInitialFlightsWithSplits match {
        case Some(fws: FlightsWithSplits) =>
          log.info(s"Received ${fws.flightsToUpdate.size} initial flights. Calculating workload.")
          val updatedWorkloads = flightLoadMinutes(fws)
          purgeExpired(updatedWorkloads, TQM.atTime, now, expireAfterMillis.toInt)
          mergeUpdatedFlightLoadMinutes(Set(), updatedWorkloads, fws)

          if (optionalInitialLoads.isEmpty) {
            val affectedTQMs = updatedWorkloads.keys.toSet
            val latestDiff = diffFromTQMs(affectedTQMs)
            loadMinutes ++= latestDiff
            purgeExpired(loadMinutes, TQM.atTime, now, expireAfterMillis.toInt)
            updatedLoadsToPush ++= latestDiff
            log.info(s"We have ${updatedLoadsToPush.size} minutes of load to push from initial flights")
          }
        case None =>
          log.warn(s"Didn't receive any initial flights to initialise with")
      }

      super.preStart()
    }

    setHandler(inFlightsWithSplits, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inFlightsWithSplits)
        val incomingFlights = grab(inFlightsWithSplits)

        log.info(s"Received ${incomingFlights.flightsToUpdate.length} updated arrivals and ${incomingFlights.arrivalsToRemove.length} arrivals to remove")

        val updatesTqms = incomingFlights.flightsToUpdate.flatMap(fws => flightTQMs.getOrElse(CodeShareKeyOrderedBySchedule(fws), List())).toSet
        val removalTqms = incomingFlights.arrivalsToRemove.flatMap(a => flightTQMs.getOrElse(CodeShareKeyOrderedBySchedule(a), List())).toSet
        val existingFlightTQMs: Set[TQM] = updatesTqms ++ removalTqms

        flightTQMs --= incomingFlights.arrivalsToRemove.map(CodeShareKeyOrderedBySchedule(_))

        val updatedWorkloads = flightLoadMinutes(incomingFlights)

        mergeUpdatedFlightLoadMinutes(existingFlightTQMs, updatedWorkloads, incomingFlights)

        val affectedTQMs = updatedWorkloads.keys.toSet ++ existingFlightTQMs
        val latestDiff = diffFromTQMs(affectedTQMs)

        loadMinutes ++= latestDiff
        purgeExpired(loadMinutes, TQM.atTime, now, expireAfterMillis.toInt)
        purgeExpired(flightTQMs, CodeShareKeyOrderedBySchedule.atTime, now, expireAfterMillis.toInt)
        updatedLoadsToPush ++= latestDiff
        log.debug(s"${updatedLoadsToPush.size} load minutes to push (${updatedLoadsToPush.values.count(_.paxLoad == 0d)} zero pax minutes)")

        pushStateIfReady()

        pullFlights()
        timer.stopAndReport()
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

    def mergeUpdatedFlightLoadMinutes(existingUpdatedFlightTQMs: Set[TQM], updatedWorkloads: mutable.SortedMap[TQM, Set[FlightSplitMinute]], incomingFlights: FlightsWithSplits): Unit = {
      val updateIds = incomingFlights.flightsToUpdate.map(CodeShareKeyOrderedBySchedule(_)).toSet
      val removalIds = incomingFlights.arrivalsToRemove.map(CodeShareKeyOrderedBySchedule(_))
      val affectedFlightIds: Set[CodeShareKeyOrderedBySchedule] = updateIds ++ removalIds

      removeExistingLoadsForUpdatedArrivals(existingUpdatedFlightTQMs, affectedFlightIds)

      addNewLoadsForUpdatedArrivals(updatedWorkloads)

      purgeExpired(flightLoadMinutes, TQM.atTime, now, expireAfterMillis.toInt)
    }

    private def addNewLoadsForUpdatedArrivals(updatedWorkloads: mutable.SortedMap[TQM, Set[FlightSplitMinute]]): Unit = {
      updatedWorkloads.foreach {
        case (tqm, newLm) =>
          val newLoadMinutes = flightLoadMinutes.getOrElse(tqm, Set()) ++ newLm
          flightLoadMinutes += (tqm -> newLoadMinutes)
      }
    }

    private def removeExistingLoadsForUpdatedArrivals(existingUpdatedFlightTQMs: Set[TQM], affectedFlightIds: Set[CodeShareKeyOrderedBySchedule]): Unit = {
      existingUpdatedFlightTQMs.foreach { tqm =>
        val existingFlightSplitsMinutes: Set[FlightSplitMinute] = flightLoadMinutes.getOrElse(tqm, Set[FlightSplitMinute]())
        val minusIncomingSplitMinutes = existingFlightSplitsMinutes.filterNot(fsm => affectedFlightIds.contains(fsm.flightId))
        flightLoadMinutes += (tqm -> minusIncomingSplitMinutes)
      }
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

    def mergeWorkloadsFromFlight(existingFlightSplitMinutes: mutable.SortedMap[TQM, Set[FlightSplitMinute]], flightWorkload: Set[FlightSplitMinute]): Unit =
      flightWorkload.foreach { fsm =>
        val tqm = TQM(fsm.terminalName, fsm.queueName, fsm.minute)
        val newFlightSplitsMinutes = existingFlightSplitMinutes.getOrElse(tqm, Set[FlightSplitMinute]()) + fsm
        existingFlightSplitMinutes += (tqm -> newFlightSplitsMinutes)
      }

    def updateTQMsForFlight(fws: ApiFlightWithSplits, flightWorkload: Set[FlightSplitMinute]): Unit = {
      val tqms = flightWorkload.map(f => TQM(f.terminalName, f.queueName, f.minute)).toList
      flightTQMs += (CodeShareKeyOrderedBySchedule(fws) -> tqms)
    }

    setHandler(outLoads, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outLoads)
        log.debug(s"outLoads onPull called")
        pushStateIfReady()
        pullFlights()
        timer.stopAndReport()
      }
    })

    def pullFlights(): Unit = {
      if (!hasBeenPulled(inFlightsWithSplits)) {
        log.debug(s"Pulling inFlightsWithSplits")
        pull(inFlightsWithSplits)
      }
    }

    def pushStateIfReady(): Unit = {
      if (updatedLoadsToPush.nonEmpty && isAvailable(outLoads)) {
        Metrics.counter(s"$stageName.minutes", updatedLoadsToPush.size)
        push(outLoads, Loads(SortedMap[TQM, LoadMinute]() ++ updatedLoadsToPush))
        updatedLoadsToPush.clear()
      }
      else log.debug(s"outLoads not available to push")
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

