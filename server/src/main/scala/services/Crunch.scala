package services

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable.{Map, Seq}
import scala.util.Success

object Crunch {
  val log = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class CrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch, paxLoad: Double, workLoad: Double, deskRec: Int, waitTime: Int)

  case class RemoveCrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch)

  case class RemoveFlight(flightId: Int)

  case class CrunchDiff(flightRemovals: Set[RemoveFlight], flightUpdates: Set[ApiFlightWithSplits], crunchMinuteRemovals: Set[RemoveCrunchMinute], crunchMinuteUpdates: Set[CrunchMinute])

  case class CrunchState(
                          crunchFirstMinuteMillis: MillisSinceEpoch,
                          numberOfMinutes: Int,
                          flights: Set[ApiFlightWithSplits],
                          crunchMinutes: Set[CrunchMinute])

  case class CrunchRequest(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch, numberOfMinutes: Int)

  val oneMinute = 60000

  class CrunchStateFullFlow(slas: Map[QueueName, Int],
                            minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                            procTimes: Map[PaxTypeAndQueue, Double],
                            groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                            validPortTerminals: Set[String])
    extends GraphStage[FlowShape[CrunchRequest, CrunchState]] {

    val in = Inlet[CrunchRequest]("CrunchState.in")
    val out = Outlet[CrunchState]("CrunchState.out")
    override val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
      var flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]] = Map()
      var crunchMinutes: Set[CrunchMinute] = Set()

      var crunchStateOption: Option[CrunchState] = None
      var crunchRunning = false

      setHandlers(in, out, new InHandler with OutHandler {
        override def onPull(): Unit = {
          log.info(s"onPull called")
          crunchStateOption match {
            case Some(crunchState) =>
              push(out, crunchState)
              crunchStateOption = None
            case None =>
              log.info(s"No CrunchState to push")
          }
          if (!hasBeenPulled(in)) pull(in)
        }

        override def onPush(): Unit = {
          log.info(s"onPush called")
          if (!crunchRunning) grabAndCrunch()

          pushStateIfReady()

          if (!hasBeenPulled(in)) pull(in)
        }

        def grabAndCrunch() = {
          crunchRunning = true

          val crunchRequest: CrunchRequest = grab(in)

          log.info(s"processing crunchRequest - ${crunchRequest.flights.length} flights")
          processFlights(crunchRequest)

          crunchRunning = false
        }

        def processFlights(crunchFlights: CrunchRequest) = {
          val newCrunchStateOption = crunch(crunchFlights)

          log.info(s"setting crunchStateOption")
          crunchStateOption = newCrunchStateOption
        }

        def crunch(crunchRequest: CrunchRequest) = {
          val relevantFlights = crunchRequest.flights.filter {
            case ApiFlightWithSplits(flight, _) =>
              validPortTerminals.contains(flight.Terminal) && (flight.PcpTime + 30) > crunchRequest.crunchStart
          }
          val uniqueFlights = groupFlightsByCodeShares(relevantFlights).map(_._1)
          log.info(s"found ${uniqueFlights.length} flights to crunch")
          val newFlightsById = uniqueFlights.map(f => (f.apiFlight.FlightID, f)).toMap
          val newFlightSplitMinutesByFlight = flightsToFlightSplitMinutes(procTimes)(uniqueFlights)

          val crunchStart = crunchRequest.crunchStart
          val numberOfMinutes = crunchRequest.numberOfMinutes
          val crunchEnd = crunchStart + (numberOfMinutes * oneMinute)
          val flightSplitDiffs: Set[FlightSplitDiff] = flightsToSplitDiffs(flightSplitMinutesByFlight, newFlightSplitMinutesByFlight)
            .filter {
              case FlightSplitDiff(_, _, _, _, _, _, minute) =>
                crunchStart <= minute && minute < crunchEnd
            }

          val crunchState = flightSplitDiffs match {
            case fsd if fsd.isEmpty =>
              log.info(s"No flight changes. No need to crunch")
              None
            case _ =>
              val newCrunchState = crunchStateFromFlightSplitMinutes(crunchStart, numberOfMinutes, newFlightsById, newFlightSplitMinutesByFlight)
              Option(newCrunchState)
          }

          flightsByFlightId = newFlightsById
          flightSplitMinutesByFlight = newFlightSplitMinutesByFlight

          crunchState
        }
      })

      def pushStateIfReady() = {
        crunchStateOption match {
          case None =>
            log.info(s"We have no state yet. Nothing to push")
          case Some(crunchState) =>
            if (isAvailable(out)) {
              log.info(s"pushing csd ${crunchState.crunchFirstMinuteMillis}")
              push(out, crunchState)
              crunchStateOption = None
            }
        }
      }

      def crunchStateFromFlightSplitMinutes(crunchStart: MillisSinceEpoch,
                                            numberOfMinutes: Int,
                                            flightsById: Map[Int, ApiFlightWithSplits],
                                            fsmsByFlightId: Map[Int, Set[FlightSplitMinute]]) = {
        val crunchResults: Set[CrunchMinute] = crunchFlightSplitMinutes(crunchStart, numberOfMinutes, fsmsByFlightId)

        CrunchState(crunchStart, numberOfMinutes, flightsById.values.toSet, crunchResults)
      }

      def crunchFlightSplitMinutes(crunchStart: MillisSinceEpoch, numberOfMinutes: Int, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]) = {
        val qlm: Set[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)
        val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Load, Load)]]] = indexQueueWorkloadsByMinute(qlm)

        val fullWlByQueue: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]] = queueMinutesForPeriod(crunchStart, numberOfMinutes)(wlByQueue)
        val eGateBankSize = 5

        val crunchResults: Set[CrunchMinute] = workloadsToCrunchMinutes(crunchStart, numberOfMinutes, fullWlByQueue, slas, minMaxDesks, eGateBankSize)
        crunchResults
      }

    }
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

  def workloadsToCrunchMinutes(crunchStartMillis: MillisSinceEpoch,
                               numberOfMinutes: Int,
                               portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]],
                               slas: Map[QueueName, Int],
                               minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                               eGateBankSize: Int): Set[CrunchMinute] = {
    val crunchEnd = crunchStartMillis + (numberOfMinutes * oneMinute)

    portWorkloads.flatMap {
      case (tn, terminalWorkloads) =>
        val terminalCrunchMinutes = terminalWorkloads.flatMap {
          case (qn, queueWorkloads) =>
            val workloadMinutes = qn match {
              case Queues.EGate => queueWorkloads.map(_._2._2 / eGateBankSize)
              case _ => queueWorkloads.map(_._2._2)
            }
            val loadByMillis = queueWorkloads.toMap
            val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
            val sla = slas.getOrElse(qn, 0)
            val queueMinMaxDesks = minMaxDesks.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
            val crunchMinutes = crunchStartMillis until crunchEnd by oneMinute
            val minDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
            val maxDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
            val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))

            val queueCrunchMinutes = triedResult match {
              case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
                val crunchMinutes = (0 until numberOfMinutes).map(minuteIdx => {
                  val minuteMillis = crunchStartMillis + (minuteIdx * oneMinute)
                  val paxLoad = loadByMillis.getOrElse(minuteMillis, (0d, 0d))._1
                  val workLoad = loadByMillis.getOrElse(minuteMillis, (0d, 0d))._2
                  CrunchMinute(tn, qn, minuteMillis, paxLoad, workLoad, deskRecs(minuteIdx), waitTimes(minuteIdx))
                }).toSet
                crunchMinutes
              case _ =>
                Set[CrunchMinute]()
            }

            queueCrunchMinutes
        }
        terminalCrunchMinutes
    }.toSet
  }

  def desksForHourOfDayInUKLocalTime(startTimeMidnightBST: MillisSinceEpoch, desks: Seq[Int]) = {
    val date = new DateTime(startTimeMidnightBST).withZone(DateTimeZone.forID("Europe/London"))
    desks(date.getHourOfDay)
  }

  def queueMinutesForPeriod(startTime: Long, numberOfMinutes: Int)
                           (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] = {
    val endTime = startTime + numberOfMinutes * oneMinute

    terminal.mapValues(queue => {
      queue.mapValues(queueWorkloadMinutes => {
        (startTime until endTime by oneMinute).map(minuteMillis =>
          (minuteMillis, queueWorkloadMinutes.getOrElse(minuteMillis, (0d, 0d)))).toList
      })
    })
  }

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
      case s@Some(_) => s
      case None => historicalSplits match {
        case s@Some(_) => s
        case None => terminalSplits match {
          case s@Some(_) => s
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

  def flightsDiff(oldFlights: Set[ApiFlightWithSplits], newFlights: Set[ApiFlightWithSplits]) = {
    val oldFlightsById = oldFlights.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap
    val newFlightsById = newFlights.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap
    val oldIds = oldFlightsById.keys.toSet
    val newIds = newFlightsById.keys.toSet
    val toRemove = (oldIds -- newIds).map {
      case id => RemoveFlight(id)
    }
    val toUpdate = newFlightsById.collect {
      case (id, cm) if oldFlightsById.get(id).isEmpty || cm != oldFlightsById(id) => cm
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinutesDiff(oldCm: Set[CrunchMinute], newCm: Set[CrunchMinute]) = {
    val oldTqmToCm = oldCm.map(cm => crunchMinuteToTqmCm(cm)).toMap
    val newTqmToCm = newCm.map(cm => crunchMinuteToTqmCm(cm)).toMap
    val oldKeys = oldTqmToCm.keys.toSet
    val newKeys = newTqmToCm.keys.toSet
    val toRemove = (oldKeys -- newKeys).map {
      case k@(tn, qn, m) => RemoveCrunchMinute(tn, qn, m)
    }
    val toUpdate = newTqmToCm.collect {
      case (k, cm) if oldTqmToCm.get(k).isEmpty || cm != oldTqmToCm(k) => cm
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinuteToTqmCm(cm: CrunchMinute) = {
    Tuple2(Tuple3(cm.terminalName, cm.queueName, cm.minute), cm)
  }

  def applyCrunchDiff(diff: CrunchDiff, cms: Set[CrunchMinute]): Set[CrunchMinute] = {
    val withoutRemovals = diff.crunchMinuteRemovals.foldLeft(cms) {
      case (soFar, removal) => soFar.filterNot {
        case CrunchMinute(tn, qn, m, _, _, _, _) => removal.terminalName == tn && removal.queueName == qn && removal.minute == m
      }
    }
    val withoutRemovalsWithUpdates = diff.crunchMinuteUpdates.foldLeft(withoutRemovals.map(crunchMinuteToTqmCm).toMap) {
      case (soFar, ncm) =>
        soFar.updated((ncm.terminalName, ncm.queueName, ncm.minute), ncm)
    }
    withoutRemovalsWithUpdates.values.toSet
  }

  def applyFlightsDiff(diff: CrunchDiff, flights: Set[ApiFlightWithSplits]): Set[ApiFlightWithSplits] = {
    val withoutRemovals = diff.flightRemovals.foldLeft(flights) {
      case (soFar, removal) => soFar.filterNot {
        case f: ApiFlightWithSplits => removal.flightId == f.apiFlight.FlightID
      }
    }
    val withoutRemovalsWithUpdates = diff.flightUpdates.foldLeft(withoutRemovals.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap) {
      case (soFar, flight) =>
        soFar.updated(flight.apiFlight.FlightID, flight)
    }
    withoutRemovalsWithUpdates.values.toSet
  }
}

