package services

import akka.NotUsed
import akka.actor.{ActorRef, ActorSystem}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable
import scala.collection.immutable.{Map, Seq}
import scala.util.{Success, Try}

object Crunch {

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadDiff(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch, paxLoad: Double, workLoad: Double)

  case class CrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch, deskRec: Int, waitTime: Int)

  case class CrunchDiff(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch, deskRec: Int, waitTime: Int)

  case class CrunchState(
                          flights: List[ApiFlightWithSplits],
                          workloads: Map[TerminalName, Map[QueueName, List[(Long, (Double, Double))]]],
                          crunchResult: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]],
                          crunchFirstMinuteMillis: MillisSinceEpoch
                        )

  case class CrunchStateDiff(crunchFirstMinuteMillis: MillisSinceEpoch, flightDiffs: Set[ApiFlightWithSplits], queueDiffs: Set[QueueLoadDiff], crunchDiffs: Set[CrunchDiff])

  case class CrunchFlights(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch, crunchEnd: MillisSinceEpoch, initialState: Boolean)

  val oneMinute = 60000
  val oneDay = 1440 * oneMinute

  trait PublisherLike {
    def publish(crunchFlights: CrunchFlights): NotUsed
  }

  case class Publisher(subscriber: ActorRef, crunchFlow: CrunchStateFlow)(implicit val mat: ActorMaterializer) extends PublisherLike {

    def something = {
      Source.actorRef(1, OverflowStrategy.dropHead)
        .via(crunchFlow)
        .to(Sink.actorRef(subscriber, "completed"))
        .run()
    }

    def publish(crunchFlights: CrunchFlights) =
      Source(List(crunchFlights))
        .via(crunchFlow)
        .to(Sink.actorRef(subscriber, "completed"))
        .run()
  }

  class CrunchStateFlow(slas: Map[QueueName, Int],
                        minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                        procTimes: Map[PaxTypeAndQueue, Double],
                        groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                        validPortTerminals: Set[String])
    extends GraphStage[FlowShape[CrunchFlights, CrunchStateDiff]] {

    val in = Inlet[CrunchFlights]("CrunchState.in")
    val out = Outlet[CrunchStateDiff]("CrunchState.out")
    override val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
      var flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]] = Map()
      var crunchMinutes: Set[CrunchMinute] = Set()
      var initialised: Boolean = false

      var crunchStateDiffOption: Option[CrunchStateDiff] = None
      var outAwaiting = false
      var crunchRunning = false

      setHandlers(in, out, new InHandler with OutHandler {
        override def onPull(): Unit = {
          crunchStateDiffOption match {
            case Some(crunchState) =>
              outAwaiting = false
              push(out, crunchState)
              crunchStateDiffOption = None
            case None =>
              outAwaiting = true
              if (!hasBeenPulled(in)) pull(in)
          }
        }

        override def onPush(): Unit = {
          if (!crunchRunning) grabAndCrunch()

          pushStateIfReady()
        }

        def grabAndCrunch() = {
          crunchRunning = true

          val crunchFlights: CrunchFlights = grab(in)
          log.info(s"grabbed ${crunchFlights.crunchStart}")

          if (initialised || crunchFlights.initialState) {
            log.info(s"processing crunchFlights - ${crunchFlights.flights.length}")
            processFlights(crunchFlights)
          } else {
            log.info(s"Ignoring CrunchFlights: not yet initialised")
          }

          crunchRunning = false
        }

        def processFlights(crunchFlights: CrunchFlights) = {
          if (crunchFlights.initialState) clearState()

          val newCrunchStateDiff = crunch(crunchFlights)

          if (!crunchFlights.initialState) {
            log.info(s"setting crunchStateDiffOption")
            crunchStateDiffOption = Option(newCrunchStateDiff)
          } else {
            log.info(s"initialised = true")
            initialised = true
            if (!hasBeenPulled(in)) pull(in)
          }
        }

        def crunch(crunchFlights: CrunchFlights) = {

          val flightsToValidTerminals = crunchFlights.flights.filter {
            case ApiFlightWithSplits(flight, _) => validPortTerminals.contains(flight.Terminal)
          }
          val uniqueFlights = groupFlightsByCodeShares(flightsToValidTerminals).map(_._1)
          val newFlightsById = crunchFlights.flights.map(f => (f.apiFlight.FlightID, f)).toMap
          val newFlightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]] = flightsToFlightSplitMinutes(procTimes)(uniqueFlights)

          val flightSplitDiffs: Set[FlightSplitDiff] = flightsToSplitDiffs(flightSplitMinutesByFlight, newFlightSplitMinutesByFlight)

          val crunchStart = crunchFlights.crunchStart

          val crunchStateDiff = flightSplitDiffs match {
            case fsd if fsd.isEmpty => emptyCrunchStateDiff(crunchStart)
            case fsd => crunchStateDiffFromFlightSplitMinutes(crunchStart, crunchFlights.crunchEnd, newFlightsById, newFlightSplitMinutesByFlight, fsd)
          }

          flightsByFlightId = newFlightsById
          flightSplitMinutesByFlight = newFlightSplitMinutesByFlight

          crunchStateDiff
        }
      })

      def pushStateIfReady() = {
        crunchStateDiffOption.foreach(crunchStateDiff =>
          if (isAvailable(out)) {
            outAwaiting = false
            log.info(s"pushing csd ${crunchStateDiff.crunchFirstMinuteMillis}")
            push(out, crunchStateDiff)
            crunchStateDiffOption = None
          })
      }

      def clearState() = {
        log.info(s"received initialState message. clearing state")
        flightsByFlightId = Map()
        flightSplitMinutesByFlight = Map()
        crunchMinutes = Set()
        crunchStateDiffOption = None
      }

      def crunchStateDiffFromFlightSplitMinutes(crunchStart: MillisSinceEpoch,
                                                        crunchEnd: MillisSinceEpoch,
                                                        flightsById: Map[Int, ApiFlightWithSplits],
                                                        fsmsByFlightId: Map[Int, Set[FlightSplitMinute]],
                                                        flightSplitDiffs: Set[FlightSplitDiff]) = {
        val flightsSinceCrunchStart: Set[ApiFlightWithSplits] = splitDiffsToFlightDiffs(flightsById, flightSplitDiffs)

        flightsSinceCrunchStart match {
          case fd if fd.isEmpty => CrunchStateDiff(crunchStart, fd, Set(), Set())
          case fd => crunchStateDiffFromFlightSplitDiffs(crunchStart, crunchEnd, fsmsByFlightId, flightSplitDiffs, fd)
        }
      }

      def crunchStateDiffFromFlightSplitDiffs(crunchStart: MillisSinceEpoch,
                                                      crunchEnd: MillisSinceEpoch,
                                                      fsmsByFlightId: Map[Int, Set[FlightSplitMinute]],
                                                      flightSplitDiffs: Set[FlightSplitDiff], fd: Set[ApiFlightWithSplits]) = {
        val queueDiffs: Set[QueueLoadDiff] = splitDiffsToQueueDiffs(flightSplitDiffs)
        queueDiffs match {
          case qd if qd.isEmpty => CrunchStateDiff(crunchStart, fd, qd, Set())
          case qd => crunchStateDiffFromFlightSplitMinutesByFlightId(crunchStart, crunchEnd, fsmsByFlightId, fd, qd)
        }
      }

      def crunchStateDiffFromFlightSplitMinutesByFlightId(crunchStart: MillisSinceEpoch,
                                                                  crunchEnd: MillisSinceEpoch,
                                                                  fsmsByFlightId: Map[Int, Set[FlightSplitMinute]],
                                                                  fd: Set[ApiFlightWithSplits],
                                                                  qd: Set[QueueLoadDiff]) = {
        val crunchResults = crunchFlightSplitMinutes(crunchStart, crunchEnd, fsmsByFlightId)
        val newCrunchMinutes: Set[CrunchMinute] = crunchMinutesFromCrunch(crunchResults, crunchStart)
        val crunchDiffs = crunchMinuteDiff(crunchMinutes, newCrunchMinutes)
        crunchMinutes = newCrunchMinutes

        CrunchStateDiff(crunchStart, fd, qd, crunchDiffs)
      }
    }

    def crunchFlightSplitMinutes(crunchStart: MillisSinceEpoch, crunchEnd: MillisSinceEpoch, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]) = {
      val qlm: Set[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)
      val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Load, Load)]]] = indexQueueWorkloadsByMinute(qlm)

      val fullWlByQueue: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]] = queueMinutesForPeriod(crunchStart, crunchEnd)(wlByQueue)
      val eGateBankSize = 5

      val crunchResults: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = queueWorkloadsToCrunchResults(crunchStart, fullWlByQueue, slas, minMaxDesks, eGateBankSize)
      crunchResults
    }
  }

  def emptyCrunchStateDiff(crunchStart: Long) = {
    CrunchStateDiff(crunchStart, Set(), Set(), Set())
  }

  def crunchMinutesFromCrunch(crunchResults: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]], startMillis: Long) = {
    val newCrunchMinutes: Set[CrunchMinute] = crunchResults.flatMap {
      case (tn, tcr) => tcr.flatMap {
        case (qn, Success(ocr)) =>
          ocr.recommendedDesks.indices.map(m => CrunchMinute(tn, qn, startMillis + (m * oneMinute), ocr.recommendedDesks(m), ocr.waitTimes(m)))
      }
    }.toSet
    newCrunchMinutes
  }

  def splitDiffsToQueueDiffs(flightSplitDiffs: Set[FlightSplitDiff]) = {
    val queueDiffs: Set[QueueLoadDiff] = flightSplitDiffs
      .groupBy(sd => (sd.terminalName, sd.queueName, sd.minute))
      .map {
        case ((tn, qn, m), fsd) => QueueLoadDiff(tn, qn, m, fsd.map(_.paxLoad).sum, fsd.map(_.workLoad).sum)
      }.toSet
    queueDiffs
  }

  def splitDiffsToFlightDiffs(newFlightsById: Map[Int, ApiFlightWithSplits], flightSplitDiffs: Set[FlightSplitDiff]) = {
    val flightDiffs: Set[ApiFlightWithSplits] = flightSplitDiffs.map(_.flightId).map(newFlightsById.get).collect {
      case Some(flight) => flight
    }
    flightDiffs
  }

  def flightsToSplitDiffs(flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]], newFlightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Set[FlightSplitDiff] = {
    val allKnownFlightIds = newFlightSplitMinutesByFlight.keys.toSet.union(flightSplitMinutesByFlight.keys.toSet)
    val flightSplitDiffs: Set[FlightSplitDiff] = allKnownFlightIds.flatMap(id => {
      val existingSplits = flightSplitMinutesByFlight.getOrElse(id, Set())
      val newSplits = newFlightSplitMinutesByFlight.getOrElse(id, Set())
      flightLoadDiff(existingSplits, newSplits)
    })
    flightSplitDiffs
  }

  def queueWorkloadsToCrunchResults(crunchStartMillis: MillisSinceEpoch,
                                    portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]],
                                    slas: Map[QueueName, Int],
                                    minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                                    eGateBankSize: Int): Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = {
    portWorkloads.map {
      case (terminalName, terminalWorkloads) =>
        val terminalCrunchResults = terminalWorkloads.map {
          case (queueName, queueWorkloads) =>
            val workloadMinutes = queueName match {
              case Queues.EGate => queueWorkloads.map(_._2._2 / eGateBankSize)
              case _ => queueWorkloads.map(_._2._2)
            }
            val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
            val sla = slas.getOrElse(queueName, 0)
            val queueMinMaxDesks = minMaxDesks.getOrElse(terminalName, Map()).getOrElse(queueName, defaultMinMaxDesks)
            val crunchEndTime = crunchStartMillis + ((workloadMinutes.length * oneMinute) - oneMinute)
            val crunchMinutes = crunchStartMillis to crunchEndTime by oneMinute
            val minDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
            val maxDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
            val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))
            (queueName, triedResult)
        }
        (terminalName, terminalCrunchResults)
    }
  }

  def desksForHourOfDayInUKLocalTime(startTimeMidnightBST: MillisSinceEpoch, desks: Seq[Int]) = {
    val date = new DateTime(startTimeMidnightBST).withZone(DateTimeZone.forID("Europe/London"))
    desks(date.getHourOfDay)
  }

  def queueMinutesForPeriod(startTime: Long, endTime: Long)
                           (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] =
    terminal.mapValues(queue => queue.mapValues(queueWorkloadMinutes =>
      List.range(startTime, endTime + oneMinute, oneMinute).map(minute => {
        (minute, queueWorkloadMinutes.getOrElse(minute, (0d, 0d)))
      })
    ))

  def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[QueueLoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]] = {
    val portLoads = queueWorkloadMinutes.groupBy(_.terminalName)

    portLoads.mapValues(terminalLoads => {
      val queueLoads = terminalLoads.groupBy(_.queueName)
      queueLoads
        .mapValues(_.map(qwl =>
          qwl.minute -> (qwl.paxLoad, qwl.workLoad)
        ).toMap)
    })
  }

  def flightsToFlightSplitMinutes(procTimes: Map[PaxTypeAndQueue, Double])(flightsWithSplits: List[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
    flightsWithSplits.map {
      case ApiFlightWithSplits(flight, splits) => (flight.FlightID, flightToFlightSplitMinutes(flight, splits, procTimes))
    }.toMap
  }

  def flightLoadDiff(oldSet: Set[FlightSplitMinute], newSet: Set[FlightSplitMinute]) = {
    val toRemove = oldSet.map(fsm => FlightSplitMinute(fsm.flightId, fsm.paxType, fsm.terminalName, fsm.queueName, -fsm.paxLoad, -fsm.workLoad, fsm.minute))
    val addAndRemoveGrouped: Map[(Int, TerminalName, QueueName, MillisSinceEpoch, PaxType), Set[FlightSplitMinute]] = newSet
      .union(toRemove)
      .groupBy(fsm => (fsm.flightId, fsm.terminalName, fsm.queueName, fsm.minute, fsm.paxType))

    addAndRemoveGrouped
      .map {
        case ((fid, tn, qn, m, pt), fsm) => FlightSplitDiff(fid, pt, tn, qn, fsm.map(_.paxLoad).sum, fsm.map(_.workLoad).sum, m)
      }
      .filterNot(fsd => fsd.paxLoad == 0 && fsd.workLoad == 0)
      .toSet
  }

  def crunchMinuteDiff(oldSet: Set[CrunchMinute], newSet: Set[CrunchMinute]) = {
    val toRemove = oldSet.map(cm => CrunchMinute(cm.terminalName, cm.queueName, cm.minute, -cm.deskRec, -cm.waitTime))
    val addAndRemoveGrouped: Map[(TerminalName, QueueName, MillisSinceEpoch), Set[CrunchMinute]] = newSet
      .union(toRemove)
      .groupBy(cm => (cm.terminalName, cm.queueName, cm.minute))

    addAndRemoveGrouped
      .map {
        case ((tn, qn, m), cm) => CrunchDiff(tn, qn, m, cm.map(_.deskRec).sum, cm.map(_.waitTime).sum)
      }
      .filterNot(cm => cm.deskRec == 0 && cm.waitTime == 0)
      .toSet
  }

  def collapseQueueLoadMinutesToSet(queueLoadMinutes: List[QueueLoadMinute]) = {
    queueLoadMinutes
      .groupBy(qlm => (qlm.terminalName, qlm.queueName, qlm.minute))
      .map {
        case ((t, q, m), qlm) =>
          val summedPaxLoad = qlm.map(_.paxLoad).sum
          val summedWorkLoad = qlm.map(_.workLoad).sum
          QueueLoadMinute(t, q, summedPaxLoad, summedWorkLoad, m)
      }.toSet
  }

  def flightToFlightSplitMinutes(flight: Arrival,
                                 splits: List[ApiSplits],
                                 procTimes: Map[PaxTypeAndQueue, Double]): Set[FlightSplitMinute] = {
    val apiSplits = splits.find(_.source == SplitSources.ApiSplitsWithCsvPercentage)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    val splitsToUseOption = apiSplits match {
      case s@Some(splits) => s
      case None => historicalSplits match {
        case s@Some(splits) => s
        case None => terminalSplits match {
          case s@Some(splits) => s
          case n@None =>
            log.error(s"Couldn't find terminal splits from AirportConfig to fall back on...")
            None
        }
      }
    }

    splitsToUseOption.map(splitsToUse => {
      val totalPax = splitsToUse.splitStyle match {
        case PaxNumbers => splitsToUse.splits.map(qc => qc.paxCount).sum
        case Percentage => BestPax.lhrBestPax(flight)
      }
      val splitRatios: Seq[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
        case PaxNumbers => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / totalPax))
        case Percentage => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
      }

      minutesForHours(flight.PcpTime, 1)
        .zip(paxDeparturesPerMinutes(totalPax.toInt, paxOffFlowRate))
        .flatMap {
          case (minuteMillis, flightPaxInMinute) =>
            splitRatios
              .filterNot(_.queueType == Queues.Transfer)
              .map(apiSplit => flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, apiSplit, splitsToUse.splitStyle))
        }.toSet
    }).getOrElse(Set())
  }

  def flightSplitMinute(flight: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int,
                        apiSplitRatio: ApiPaxTypeAndQueueCount,
                        splitStyle: SplitStyle): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val splitWorkLoadInMinute = splitPaxInMinute * procTimes(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType))
    FlightSplitMinute(flight.FlightID, apiSplitRatio.passengerType, flight.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
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

  def getLocalLastMidnight(now: SDateLike) = {
    val localMidnight = s"${now.getFullYear}-${now.getMonth}-${now.getDate}T00:00"
    SDate(localMidnight, DateTimeZone.forID("Europe/London"))
  }
}

