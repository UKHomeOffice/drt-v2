package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi.{MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.core.SplitsCalculator
import services._
import services.graphstages.Crunch.{log, _}
import services.workloadcalculator.PaxLoadCalculator.Load

import scala.collection.immutable
import scala.collection.immutable.Map
import scala.language.postfixOps


case class LoadMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch, pax: Double, work: Double)

class WorkloadGraphStage(name: String,
                         optionalInitialFlights: Option[FlightsWithSplits],
                         airportConfig: AirportConfig,
                         natProcTimes: Map[String, Double],
                         groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                         splitsCalculator: SplitsCalculator,
                         crunchStartFromFirstPcp: (SDateLike) => SDateLike = getLocalLastMidnight,
                         crunchEndFromLastPcp: (SDateLike) => SDateLike = (_) => getLocalNextMidnight(SDate.now()),
                         earliestAndLatestAffectedPcpTime: (Set[ApiFlightWithSplits], Set[ApiFlightWithSplits]) => Option[(SDateLike, SDateLike)],
                         expireAfterMillis: Long,
                         now: () => SDateLike,
                         maxDaysToCrunch: Int,
                         waitForManifests: Boolean = true,
                         minutesToCrunch: Int,
                         warmUpMinutes: Int,
                         useNationalityBasedProcessingTimes: Boolean)
  extends GraphStage[FlowShape[FlightsWithSplits, PortState]] {

  val inFlightsWithSplits: Inlet[FlightsWithSplits] = Inlet[FlightsWithSplits]("inFlightsWithSplits.in")
  val outCrunch: Outlet[PortState] = Outlet[PortState]("PortStateOut.out")

  override val shape = new FlowShape(inFlightsWithSplits, outCrunch)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
    var portStateOption: Option[PortState] = None

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      optionalInitialFlights match {
        case Some(FlightsWithSplits(flights)) =>
          log.info(s"Received ${flights.size} initial flight with splits")
          flightsByFlightId = purgeExpiredArrivals(
            flights
              .map(f => Tuple2(f.apiFlight.uniqueId, f))
              .toMap)
        case _ =>
          log.warn(s"Did not receive any flights to initialise with")
      }

      super.preStart()
    }

    setHandler(outCrunch, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"crunchOut onPull called")
        pushStateIfReady()
        pullAll()
      }
    })

    def pullAll(): Unit = {
      if (!hasBeenPulled(inFlightsWithSplits)) {
        log.info(s"Pulling inFlightsWithSplits")
        pull(inFlightsWithSplits)
      }
    }

    setHandler(inFlightsWithSplits, new InHandler {
      override def onPush(): Unit = {
        log.debug(s"inFlights onPush called")
        val incomingFlights = grab(inFlightsWithSplits)

        log.info(s"Grabbed ${incomingFlights.flights.length} flights")

        updateWorkloadsIfAppropriate(incomingFlights.flights.toSet, flightsByFlightId.values.toSet)

        pullAll()
      }
    })

    def updateWorkloadsIfAppropriate(updatedFlights: Set[ApiFlightWithSplits], existingFlights: Set[ApiFlightWithSplits]): Unit = {
      val earliestAndLatest = earliestAndLatestAffectedPcpTime(existingFlights, updatedFlights)
      log.info(s"Latest PCP times: $earliestAndLatest")

      earliestAndLatest.foreach {
        case (earliest, latest) =>
          val crunchStart = crunchStartFromFirstPcp(earliest)
          val crunchEnd = crunchEndFromLastPcp(latest)
          log.info(s"Crunch period ${crunchStart.toLocalDateTimeString()} to ${crunchEnd.toLocalDateTimeString()}")
          val loadUpdate: Set[QueueLoadMinute] = loadForUpdates(updatedFlights, crunchStart, crunchEnd)
          pushStateIfReady()
      }
    }

    def loadForUpdates(flights: Set[ApiFlightWithSplits], crunchStart: SDateLike, crunchEnd: SDateLike): Set[QueueLoadMinute] = {
      val start = crunchStart.addMinutes(-1 * warmUpMinutes)

      val scheduledFlightsInCrunchWindow = flights
        .toList
        .filter(_.apiFlight.Status != "Cancelled")
        .filter(f => isFlightInTimeWindow(f, start, crunchEnd))

      log.info(s"Requesting crunch for ${scheduledFlightsInCrunchWindow.length} flights after flights update")
      val uniqueFlights = groupFlightsByCodeShares(scheduledFlightsInCrunchWindow).map(_._1)
      val newFlightSplitMinutesByFlight = flightsToFlightSplitMinutes(airportConfig.defaultProcessingTimes.head._2, useNationalityBasedProcessingTimes)(uniqueFlights)
      val earliestMinute: MillisSinceEpoch = newFlightSplitMinutesByFlight.values.flatMap(_.map(identity)).toList match {
        case fsm if fsm.nonEmpty => fsm.map(_.minute).min
        case _ => 0L
      }
      log.debug(s"Earliest flight split minute: ${SDate(earliestMinute).toLocalDateTimeString()}")
      val numberOfMinutes = ((crunchEnd.millisSinceEpoch - crunchStart.millisSinceEpoch) / 60000).toInt
      log.debug(s"Crunching $numberOfMinutes minutes")
      loadsByQueueAndTerminal(crunchStart.millisSinceEpoch, numberOfMinutes, newFlightSplitMinutesByFlight)
    }

    def pushStateIfReady(): Unit = {
      portStateOption match {
        case None => log.info(s"We have no PortState yet. Nothing to push")
        case Some(portState) =>
          if (isAvailable(outCrunch)) {
            log.info(s"Pushing PortState: ${portState.crunchMinutes.size} cms, ${portState.staffMinutes.size} sms, ${portState.flights.size} fts")
            push(outCrunch, portState)
            portStateOption = None
          } else log.info(s"outCrunch not available to push")
      }
    }

    def loadsByQueueAndTerminal(crunchStart: MillisSinceEpoch, numberOfMinutes: Int, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Set[QueueLoadMinute] = {
      val qlm: Set[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)

      queueMinutesForPeriod(crunchStart - warmUpMinutes * oneMinuteMillis, numberOfMinutes + warmUpMinutes)(qlm)
    }

    def queueMinutesForPeriod(startTime: Long, numberOfMinutes: Int)
                             (loads: Set[QueueLoadMinute]): Set[QueueLoadMinute] = {
      val endTime = startTime + numberOfMinutes * oneMinuteMillis

      (startTime until endTime by oneMinuteMillis).flatMap(minute => {
        airportConfig.queues.flatMap {
          case (tn, queues) => queues.map(qn => {
            loads
              .find(m => m.minute == minute && m.terminalName == tn && m.queueName == qn)
              .getOrElse(QueueLoadMinute(tn, qn, minute, 0, 0))
          })
        }
      }).toSet
    }

    def flightsToFlightSplitMinutes(portProcTimes: Map[PaxTypeAndQueue, Double], useNationalityBasedProcessingTimes: Boolean)(flightsWithSplits: List[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
      flightsWithSplits
        .map(flightWithSplits => {
          val flightSplitMinutes = WorkloadCalculator.flightToFlightSplitMinutes(flightWithSplits, portProcTimes, natProcTimes, useNationalityBasedProcessingTimes)
          (flightWithSplits.apiFlight.uniqueId, flightSplitMinutes)
        })
        .toMap
    }

    def flightSplitMinutesToQueueLoadMinutes(flightToFlightSplitMinutes: Map[Int, Set[FlightSplitMinute]]): Set[QueueLoadMinute] = {
      flightToFlightSplitMinutes
        .values
        .flatten
        .groupBy(s => (s.terminalName, s.queueName, s.minute)).map {
        case ((terminalName, queueName, minute), fsms) =>
          val paxLoad = fsms.map(_.paxLoad).sum
          val workLoad = fsms.map(_.workLoad).sum
          QueueLoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
      }.toSet
    }
  }

  def purgeExpiredArrivals(arrivals: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
    val expired = hasExpiredForType((a: ApiFlightWithSplits) => a.apiFlight.PcpTime)
    val updated = arrivals.filterNot { case (_, a) => expired(a) }

    val numPurged = arrivals.size - updated.size
    if (numPurged > 0) log.info(s"Purged $numPurged expired arrivals")

    updated
  }

  def hasExpiredForType[A](toMillis: A => MillisSinceEpoch): A => Boolean = {
    Crunch.hasExpired[A](now(), expireAfterMillis, toMillis)
  }

  def isFlightInTimeWindow(f: ApiFlightWithSplits, crunchStart: SDateLike, crunchEnd: SDateLike): Boolean = {
    val startPcpTime = f.apiFlight.PcpTime
    val endPcpTime = f.apiFlight.PcpTime + (ArrivalHelper.bestPax(f.apiFlight) / 20) * oneMinuteMillis
    crunchStart.millisSinceEpoch <= endPcpTime && startPcpTime < crunchEnd.millisSinceEpoch
  }
}
