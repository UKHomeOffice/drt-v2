package services.graphstages

import drt.shared.Crunch.{CrunchMinute, MillisSinceEpoch}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.{Logger, LoggerFactory}
import services._
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable.{Map, Seq}
import scala.util.Success

object Crunch {
  val log: Logger = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class RemoveCrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch) {
    lazy val key = s"$terminalName$queueName$minute".hashCode
  }

  case class RemoveFlight(flightId: Int)

  case class CrunchDiff(flightRemovals: Set[RemoveFlight], flightUpdates: Set[ApiFlightWithSplits], crunchMinuteRemovals: Set[RemoveCrunchMinute], crunchMinuteUpdates: Set[CrunchMinute])

  case class CrunchRequest(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch)

  val oneMinuteMillis: MillisSinceEpoch = 60000L
  val oneHourMillis: MillisSinceEpoch = oneMinuteMillis * 60
  val oneDayMillis: MillisSinceEpoch = oneHourMillis * 24

  def flightVoyageNumberPadded(arrival: Arrival): String = {
    val number = FlightParsing.parseIataToCarrierCodeVoyageNumber(arrival.IATA)
    val vn = padTo4Digits(number.map(_._2).getOrElse("-"))
    vn
  }

  def midnightThisMorning: MillisSinceEpoch = {
    val localNow = SDate(new DateTime(DateTimeZone.forID("Europe/London")).getMillis)
    val crunchStartDate = Crunch.getLocalLastMidnight(localNow).millisSinceEpoch
    crunchStartDate
  }

  def padTo4Digits(voyageNumber: String): String = {
    val prefix = voyageNumber.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + voyageNumber
  }

  def haveWorkloadsChanged(flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]], newFlightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Boolean = {
    val allKnownFlightIds = newFlightSplitMinutesByFlight.keys.toSet.union(flightSplitMinutesByFlight.keys.toSet)
    allKnownFlightIds
      .find(id => {
        val existingSplits = flightSplitMinutesByFlight.getOrElse(id, Set())
        val newSplits = newFlightSplitMinutesByFlight.getOrElse(id, Set())
        existingSplits != newSplits
      }) match {
      case None => false
      case Some(_) => true
    }
  }

  def workloadsToCrunchMinutes(crunchStartMillis: MillisSinceEpoch,
                               numberOfMinutes: Int,
                               portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]],
                               slas: Map[QueueName, Int],
                               minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                               eGateBankSize: Int): Map[Int, CrunchMinute] = {
    val crunchEnd = crunchStartMillis + (numberOfMinutes * oneMinuteMillis)

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
            val crunchMinutes = crunchStartMillis until crunchEnd by oneMinuteMillis
            val minDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
            val maxDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
            val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))

            val queueCrunchMinutes = triedResult match {
              case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
                val crunchMinutes = (0 until numberOfMinutes).map(minuteIdx => {
                  val minuteMillis = crunchStartMillis + (minuteIdx * oneMinuteMillis)
                  val paxLoad = loadByMillis.getOrElse(minuteMillis, (0d, 0d))._1
                  val workLoad = loadByMillis.getOrElse(minuteMillis, (0d, 0d))._2
                  CrunchMinute(tn, qn, minuteMillis, paxLoad, workLoad, deskRecs(minuteIdx), waitTimes(minuteIdx))
                }).toSet
                crunchMinutes.map(cm => (cm.key, cm)).toMap
              case _ =>
                Map[Int, CrunchMinute]()
            }

            queueCrunchMinutes
        }
        terminalCrunchMinutes
    }
  }

  def desksForHourOfDayInUKLocalTime(dateTimeMillis: MillisSinceEpoch, desks: Seq[Int]): Int = {
    val date = new DateTime(dateTimeMillis).withZone(DateTimeZone.forID("Europe/London"))
    desks(date.getHourOfDay)
  }

  def queueMinutesForPeriod(startTime: Long, numberOfMinutes: Int)
                           (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] = {
    val endTime = startTime + numberOfMinutes * oneMinuteMillis

    terminal.mapValues(queue => {
      queue.mapValues(queueWorkloadMinutes => {
        (startTime until endTime by oneMinuteMillis).map(minuteMillis =>
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
          (qwl.minute, (qwl.paxLoad, qwl.workLoad))
        ).toMap)
    })
  }

  def flightsToFlightSplitMinutes(procTimes: Map[PaxTypeAndQueue, Double])(flightsWithSplits: List[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
    flightsWithSplits.map {
      case ApiFlightWithSplits(flight, splits, _) => (flight.uniqueId, flightToFlightSplitMinutes(flight, splits, procTimes))
    }.toMap
  }

  def flightLoadDiff(oldSet: Set[FlightSplitMinute], newSet: Set[FlightSplitMinute]): Set[FlightSplitDiff] = {
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

  def collapseQueueLoadMinutesToSet(queueLoadMinutes: List[QueueLoadMinute]): Set[QueueLoadMinute] = {
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
                                 splits: Set[ApiSplits],
                                 procTimes: Map[PaxTypeAndQueue, Double]): Set[FlightSplitMinute] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithCsvPercentage && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithCsvPercentage && s.eventType.contains(DqEventCodes.CheckIn))
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    val splitsToUseOption = apiSplitsDc match {
      case s@Some(_) => s
      case None => apiSplitsCi match {
        case s@Some(_) => s
        case None => historicalSplits match {
          case s@Some(_) => s
          case None => terminalSplits match {
            case s@Some(_) => s
            case None =>
              log.error(s"Couldn't find terminal splits from AirportConfig to fall back on...")
              None
          }
        }
      }
    }

    splitsToUseOption.map(splitsToUse => {
      val totalPax = splitsToUse.splitStyle match {
        case PaxNumbers => splitsToUse.splits.map(qc => qc.paxCount).sum
        case Percentage => ArrivalHelper.bestPax(flight)
        case UndefinedSplitStyle => 0
      }
      val splitRatios: Set[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
        case PaxNumbers => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / totalPax))
        case Percentage => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
        case UndefinedSplitStyle => splitsToUse.splits.map(qc => qc.copy(paxCount = 0))
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
    FlightSplitMinute(flight.uniqueId, apiSplitRatio.passengerType, flight.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
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

  def getLocalLastMidnight(now: SDateLike): SDateLike = {
    val localMidnight = s"${now.getFullYear()}-${now.getMonth()}-${now.getDate()}T00:00"
    SDate(localMidnight, DateTimeZone.forID("Europe/London"))
  }

  def getLocalNextMidnight(now: SDateLike): SDateLike = {
    val nextDay = now.addDays(1)
    val localMidnight = s"${nextDay.getFullYear()}-${nextDay.getMonth()}-${nextDay.getDate()}T00:00"
    SDate(localMidnight, DateTimeZone.forID("Europe/London"))
  }

  def flightsDiff(oldFlightsById: Map[Int, ApiFlightWithSplits], newFlightsById: Map[Int, ApiFlightWithSplits]): (Set[RemoveFlight], Set[ApiFlightWithSplits]) = {
    val oldIds = oldFlightsById.keys.toSet
    val newIds = newFlightsById.keys.toSet
    val toRemove = (oldIds -- newIds).map(RemoveFlight)
    val toUpdate = newFlightsById.collect {
      case (id, f) if oldFlightsById.get(id).isEmpty || !f.equals(oldFlightsById(id)) => f
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinutesDiff(oldTqmToCm: Map[Int, CrunchMinute], newTqmToCm: Map[Int, CrunchMinute]): (Set[RemoveCrunchMinute], Set[CrunchMinute]) = {
    val oldKeys = oldTqmToCm.values.map(cm => Tuple3(cm.terminalName, cm.queueName, cm.minute)).toSet
    val newKeys = newTqmToCm.values.map(cm => Tuple3(cm.terminalName, cm.queueName, cm.minute)).toSet
    val toRemove = (oldKeys -- newKeys).map {
      case (tn, qn, m) => RemoveCrunchMinute(tn, qn, m)
    }
    val toUpdate = newTqmToCm.collect {
      case (k, cm) if oldTqmToCm.get(k).isEmpty || !cm.equals(oldTqmToCm(k)) => cm
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinuteToTqmCm(cm: CrunchMinute): ((TerminalName, QueueName, MillisSinceEpoch), CrunchMinute) = {
    Tuple2(Tuple3(cm.terminalName, cm.queueName, cm.minute), cm)
  }

  def applyCrunchDiff(diff: CrunchDiff, cms: Map[Int, CrunchMinute]): Map[Int, CrunchMinute] = {
    val nowMillis = SDate.now().millisSinceEpoch
    val withoutRemovals = diff.crunchMinuteRemovals.foldLeft(cms) {
      case (soFar, removeCm) => soFar - removeCm.key
    }
    val withoutRemovalsWithUpdates = diff.crunchMinuteUpdates.foldLeft(withoutRemovals) {
      case (soFar, ncm) => soFar.updated(ncm.key, ncm.copy(lastUpdated = Option(nowMillis)))
    }
    withoutRemovalsWithUpdates
  }

  def applyFlightsWithSplitsDiff(diff: CrunchDiff, flights: Map[Int, ApiFlightWithSplits]): Map[Int, ApiFlightWithSplits] = {
    val nowMillis = SDate.now().millisSinceEpoch
    val withoutRemovals = diff.flightRemovals.foldLeft(flights) {
      case (soFar, removeFlight) => soFar - removeFlight.flightId
    }
    val withoutRemovalsWithUpdates = diff.flightUpdates.foldLeft(withoutRemovals) {
      case (soFar, flight) => soFar.updated(flight.apiFlight.uniqueId, flight.copy(lastUpdated = Option(nowMillis)))
    }
    withoutRemovalsWithUpdates
  }
}
