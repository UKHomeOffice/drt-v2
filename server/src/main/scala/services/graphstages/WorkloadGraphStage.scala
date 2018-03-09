package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._

import scala.collection.immutable.Map
import scala.language.postfixOps


class WorkloadGraphStage(optionalInitialLoads: Option[Loads],
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
    var workloadByFlightId: Map[Int, Set[FlightSplitMinute]] = Map()
    var loadMinutes: Set[LoadMinute] = Set()
    var loadsToPush: Map[Int, LoadMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(getClass)

    override def preStart(): Unit = {
      loadMinutes = optionalInitialLoads match {
        case Some(Loads(lms)) =>
          log.info(s"Received ${lms.size} initial loads")
          Crunch.purgeExpired(lms, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)
        case _ =>
          log.warn(s"Did not receive any loads to initialise with")
          Set()
      }
      workloadByFlightId = optionalInitialFlightsWithSplits match {
        case Some(FlightsWithSplits(initialFlights)) =>
          log.info(s"Received ${initialFlights.size} initial flights. Calculating workload.")
          purgeExpired(flightsToWorkloadByFlightId(initialFlights), (fsms: Set[FlightSplitMinute]) => fsms.map(_.minute).min, now, expireAfterMillis)
        case None =>
          log.warn(s"Didn't receive any initial flights to initialise with")
          Map()
      }

      super.preStart()
    }

    def flightsToWorkloadByFlightId(initialFlights: Seq[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
      initialFlights.map(fws => {
        val uniqueFlightId = fws.apiFlight.uniqueId
        val flightWorkload = WorkloadCalculator.flightToFlightSplitMinutes(fws, airportConfig.defaultProcessingTimes.head._2, natProcTimes, useNationalityBasedProcessingTimes)

        (uniqueFlightId, flightWorkload)
      }).toMap
    }

    setHandler(inFlightsWithSplits, new InHandler {
      override def onPush(): Unit = {
        val incomingFlights = grab(inFlightsWithSplits)
        log.info(s"Received ${incomingFlights.flights.size} arrivals $incomingFlights")
        /*
        1) map over each incoming flights to produce new workload
        2) update flightId -> workload map
        3) merge all workloads to Set[LoadMinute]
        4) calc new/old LoadMinute diff and push
         */

        val updatedWorkloads: Map[Int, Set[FlightSplitMinute]] = mergeWorkloadByFlightId(incomingFlights, workloadByFlightId)
        log.info(s"updatedWorkloads: $updatedWorkloads")
        workloadByFlightId = purgeExpired(updatedWorkloads, (fsms: Set[FlightSplitMinute]) => fsms.map(_.minute).min, now, expireAfterMillis)
        val updatedLoads: Set[LoadMinute] = flightSplitMinutesToQueueLoadMinutes(updatedWorkloads)

        val diff = loadDiff(updatedLoads, loadMinutes)
        loadMinutes = purgeExpired(updatedLoads, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        loadsToPush = purgeExpired(mergeLoadMinutes(diff, loadsToPush), (lm: LoadMinute) => lm.minute, now, expireAfterMillis)
        log.info(s"Now have ${loadsToPush.size} load minutes to push")

        pushStateIfReady()

        pullFlights()
      }
    })

    def mergeLoadMinutes(updatedLoads: Set[LoadMinute], existingLoads: Map[Int, LoadMinute]): Map[Int, LoadMinute] = {
      updatedLoads.foldLeft(existingLoads) {
        case (soFar, newLoadMinute) => soFar.updated(newLoadMinute.uniqueId, newLoadMinute)
      }
    }

    def loadDiff(updatedLoads: Set[LoadMinute], existingLoads: Set[LoadMinute]): Set[LoadMinute] = {
      val updates = updatedLoads -- existingLoads
      val removeIds = (existingLoads.map(_.uniqueId) -- updatedLoads.map(_.uniqueId))
      val removes = existingLoads.filter(l => removeIds.contains(l.uniqueId)).map(_.copy(paxLoad = 0, workLoad = 0))
      val diff = updates ++ removes
      log.info(s"${diff.size} updated load minutes")

      diff
    }

    def mergeWorkloadByFlightId(incomingFlights: FlightsWithSplits, existingLoads: Map[Int, Set[FlightSplitMinute]]): Map[Int, Set[FlightSplitMinute]] = {
      incomingFlights.flights.foldLeft(existingLoads) {
        case (soFar, fws) =>
          val uniqueFlightId = fws.apiFlight.uniqueId
          val flightWorkload = WorkloadCalculator.flightToFlightSplitMinutes(fws, airportConfig.defaultProcessingTimes.head._2, natProcTimes, useNationalityBasedProcessingTimes)

          soFar.updated(uniqueFlightId, flightWorkload)
      }
    }

    setHandler(outLoads, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"outLoads onPull called")
        pushStateIfReady()
        pullFlights()
      }
    })

    def pullFlights(): Unit = {
      if (!hasBeenPulled(inFlightsWithSplits)) {
        log.info(s"Pulling inFlightsWithSplits")
        pull(inFlightsWithSplits)
      }
    }

    def pushStateIfReady(): Unit = {
      if (loadsToPush.isEmpty) log.info(s"We have no load minutes. Nothing to push")
      else if (isAvailable(outLoads)) {
        log.info(s"Pushing ${loadsToPush.size} load minutes")
        push(outLoads, Loads(loadsToPush.values.toSet))
        loadsToPush = Map()
      } else log.info(s"outLoads not available to push")
    }

    def flightSplitMinutesToQueueLoadMinutes(flightToFlightSplitMinutes: Map[Int, Set[FlightSplitMinute]]): Set[LoadMinute] = {
      flightToFlightSplitMinutes
        .values
        .flatten
        .groupBy(s => (s.terminalName, s.queueName, s.minute)).map {
        case ((terminalName, queueName, minute), fsms) =>
          val paxLoad = fsms.map(_.paxLoad).sum
          val workLoad = fsms.map(_.workLoad).sum
          LoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
      }.toSet
    }
  }
}
