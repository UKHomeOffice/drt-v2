package drt.shared

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, _}
import drt.shared.SplitRatiosNs.SplitSources

import scala.concurrent.Future
import scala.util.matching.Regex

object DeskAndPaxTypeCombinations {
  val egate = "eGate"
  val deskEeaNonMachineReadable = "EEA NMR"
  val deskEea = "EEA"
  val nationalsDeskVisa = "VISA"
  val nationalsDeskNonVisa = "Non-VISA"
}

case class MilliDate(millisSinceEpoch: Long) extends Ordered[MilliDate] {
  def compare(that: MilliDate): Int = millisSinceEpoch.compare(that.millisSinceEpoch)
}

object FlightParsing {
  val iataRe: Regex = """([A-Z0-9]{2})(\d{1,4})(\w)?""".r
  val icaoRe: Regex = """([A-Z]{2,3})(\d{1,4})(\w)?""".r

  def parseIataToCarrierCodeVoyageNumber(iata: String): Option[(String, String)] = {
    iata match {
      case iataRe(carriercode, voyageNumber, _) => Option((carriercode, voyageNumber))
      case icaoRe(carriercode, voyageNumber, _) => Option((carriercode, voyageNumber))
      case what => None
    }
  }
}

sealed trait SplitStyle {
  def name: String = getClass.getSimpleName
}

object SplitStyle {
  def apply(splitStyle: String): SplitStyle = splitStyle match {
    case "PaxNumbers$" => PaxNumbers
    case "Percentage$" => Percentage
    case "Ratio" => Ratio
    case _ => UndefinedSplitStyle
  }
}

case object PaxNumbers extends SplitStyle

case object Percentage extends SplitStyle

case object Ratio extends SplitStyle

case object UndefinedSplitStyle extends SplitStyle

case class ApiPaxTypeAndQueueCount(passengerType: PaxType, queueType: String, paxCount: Double, nationalities: Option[Map[String, Double]])

case class ApiSplits(splits: Set[ApiPaxTypeAndQueueCount], source: String, eventType: Option[String], splitStyle: SplitStyle = PaxNumbers) {
  lazy val totalExcludingTransferPax: Double = ApiSplits.totalExcludingTransferPax(splits)
  lazy val totalPax: Double = ApiSplits.totalPax(splits)
}

case class StaffTimeSlot(
                          terminal: String,
                          start: MillisSinceEpoch,
                          staff: Int,
                          durationMillis: Int
                        )

case class StaffTimeSlotsForTerminalMonth(
                                           monthMillis: MillisSinceEpoch,
                                           terminal: TerminalName,
                                           timeSlots: Seq[StaffTimeSlot]
                                         )

case class MonthOfRawShifts(month: MillisSinceEpoch, shifts: String)

object ApiSplits {
  def totalExcludingTransferPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.filter(s => s.queueType != Queues.Transfer).toList.map(_.paxCount).sum

  def totalPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toList.map(_.paxCount).sum
}

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[ApiSplits], lastUpdated: Option[MillisSinceEpoch] = None) {
  def equals(candidate: ApiFlightWithSplits): Boolean = {
    this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)
  }

  def bestSplits: Option[ApiSplits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.CheckIn))
    val predictedSplits = splits.find(s => s.source == SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    List(apiSplitsDc, apiSplitsCi, predictedSplits, historicalSplits, terminalSplits).find {
      case Some(_) => true
      case _ => false
    }.getOrElse {
      None
    }
  }

  def apiSplits: Option[ApiSplits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.CheckIn))

    List(apiSplitsDc, apiSplitsCi).find {
      case Some(_) => true
      case _ => false
    }.getOrElse {
      None
    }
  }
}

case class FlightsNotReady()

case class Arrival(
                    Operator: String,
                    Status: String,
                    EstDT: String,
                    ActDT: String,
                    EstChoxDT: String,
                    ActChoxDT: String,
                    Gate: String,
                    Stand: String,
                    MaxPax: Int,
                    ActPax: Int,
                    TranPax: Int,
                    RunwayID: String,
                    BaggageReclaimId: String,
                    FlightID: Int,
                    AirportID: String,
                    Terminal: String,
                    rawICAO: String,
                    rawIATA: String,
                    Origin: String,
                    SchDT: String,
                    Scheduled: Long,
                    PcpTime: Long,
                    LastKnownPax: Option[Int] = None) {
  lazy val ICAO: String = Arrival.standardiseFlightCode(rawICAO)
  lazy val IATA: String = Arrival.standardiseFlightCode(rawIATA)

  lazy val flightNumber: Int = {
    val bestCode = (IATA, ICAO) match {
      case (iata, _) if iata != "" => iata
      case (_, icao) if icao != "" => icao
      case _ => ""
    }

    bestCode match {
      case Arrival.flightCodeRegex(_, fn, _) => fn.toInt
      case _ => 0
    }
  }

  lazy val uniqueId: Int = s"$Terminal$Scheduled$flightNumber}".hashCode
}

object Arrival {
  val flightCodeRegex: Regex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

  def summaryString(arrival: Arrival): TerminalName = arrival.AirportID + "/" + arrival.Terminal + "@" + arrival.SchDT + "!" + arrival.IATA

  def standardiseFlightCode(flightCode: String): String = {
    val flightCodeRegex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

    flightCode match {
      case flightCodeRegex(operator, flightNumber, suffix) =>
        val number = f"${flightNumber.toInt}%04d"
        f"$operator$number$suffix"
      case _ => flightCode
    }
  }
}

trait SDateLike {

  def ddMMyyString: String = f"${getDate}%02d/${getMonth}%02d/${getFullYear - 2000}%02d"

  /**
    * Days of the week 1 to 7 (Monday is 1)
    *
    * @return
    */
  def getDayOfWeek(): Int

  def getFullYear(): Int

  def getMonth(): Int

  def getDate(): Int

  def getHours(): Int

  def getMinutes(): Int

  def millisSinceEpoch: MillisSinceEpoch

  def toISOString(): String

  def addDays(daysToAdd: Int): SDateLike

  def addMonths(monthsToAdd: Int): SDateLike

  def addHours(hoursToAdd: Int): SDateLike

  def addMinutes(minutesToAdd: Int): SDateLike

  def addMillis(millisToAdd: Int): SDateLike

  def toLocalDateTimeString(): String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d ${getHours()}%02d:${getMinutes()}%02d"

  def toISODateOnly: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d"

  def toHoursAndMinutes(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  def prettyDateTime(): String = f"${getDate()}%02d-${getMonth()}%02d-${getFullYear()} ${getHours()}%02d:${getMinutes()}%02d"

  def prettyTime(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  override def toString: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02dT${getHours()}%02d${getMinutes()}%02d"

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case d: SDateLike =>
        d.millisSinceEpoch == millisSinceEpoch
      case _ => false
    }
  }
}


object CrunchResult {
  def empty = CrunchResult(0, 0, Vector[Int](), List())
}


case class CrunchResult(
                         firstTimeMillis: Long,
                         intervalMillis: Long,
                         recommendedDesks: IndexedSeq[Int],
                         waitTimes: Seq[Int])

case class AirportInfo(airportName: String, city: String, country: String, code: String)

object FlightsApi {

  case class Flights(flights: Seq[Arrival])

  case class FlightsWithSplits(flights: Seq[ApiFlightWithSplits])

  type TerminalName = String

  type QueueName = String
}

object PassengerSplits {
  type QueueType = String

  type PaxTypeAndQueueCounts = Seq[ApiPaxTypeAndQueueCount]


  case object FlightsNotFound

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: MilliDate)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: MilliDate,
                             paxSplits: List[ApiPaxTypeAndQueueCount])

}

case class DeskStat(desks: Option[Int], waitTime: Option[Int])

case class ActualDeskStats(desks: Map[String, Map[String, Map[Long, DeskStat]]])

object CrunchApi {
  type MillisSinceEpoch = Long

  case class CrunchState(flights: Set[ApiFlightWithSplits],
                         crunchMinutes: Set[CrunchMinute],
                         staffMinutes: Set[StaffMinute])

  case class PortState(flights: Map[Int, ApiFlightWithSplits],
                       crunchMinutes: Map[Int, CrunchMinute],
                       staffMinutes: Map[Int, StaffMinute])

  sealed trait Minute {
    val minute: MillisSinceEpoch
    val lastUpdated: Option[MillisSinceEpoch]
    val terminalName: TerminalName
  }

  case class StaffMinute(terminalName: TerminalName,
                         minute: MillisSinceEpoch,
                         shifts: Int,
                         fixedPoints: Int,
                         movements: Int,
                         lastUpdated: Option[MillisSinceEpoch] = None) extends Minute {
    def equals(candidate: StaffMinute): Boolean =
      this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: Int = s"$terminalName$minute".hashCode
    lazy val available = shifts + movements match {
      case sa if sa >= 0 => sa
      case _ => 0
    }
  }

  object StaffMinute {
    def empty = StaffMinute("", 0L, 0, 0, 0, None)
  }

  case class LoadMinute()

  case class CrunchMinute(terminalName: TerminalName,
                          queueName: QueueName,
                          minute: MillisSinceEpoch,
                          paxLoad: Double,
                          workLoad: Double,
                          deskRec: Int,
                          waitTime: Int,
                          deployedDesks: Option[Int] = None,
                          deployedWait: Option[Int] = None,
                          actDesks: Option[Int] = None,
                          actWait: Option[Int] = None,
                          lastUpdated: Option[MillisSinceEpoch] = None) extends Minute {
    def equals(candidate: CrunchMinute): Boolean =
      this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: Int = s"$terminalName$queueName$minute".hashCode
  }

  case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])

  case class CrunchUpdates(latest: MillisSinceEpoch, flights: Set[ApiFlightWithSplits], minutes: Set[CrunchMinute], staff: Set[StaffMinute])

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  case class ForecastPeriod(days: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  case class ForecastHeadlineFigures(queueDayHeadlines: Set[QueueHeadline])

  case class QueueHeadline(day: MillisSinceEpoch, queue: QueueName, paxNos: Int, workload: Int)

  def staffByTimeSlot(slotSize: Int)(staffMinutes: Set[StaffMinute]): Map[MillisSinceEpoch, Int] = {
    staffMinutes.toList.sortBy(_.minute).grouped(slotSize).toList.map(slot => {
      slot.map(_.minute).min -> slot.map(_.shifts).min
    }).toMap
  }

  def fixedPointsByTimeSlot(slotSize: Int)(staffMinutes: Set[StaffMinute]): Map[MillisSinceEpoch, Int] = {
    staffMinutes.toList.sortBy(_.minute).grouped(slotSize).toList.map(slot => {
      slot.map(_.minute).min -> slot.map(_.fixedPoints).max
    }).toMap
  }

  def groupCrunchMinutesByX(groupSize: Int)(crunchMinutes: Seq[(MillisSinceEpoch, Set[CrunchMinute])], terminalName: TerminalName, queueOrder: List[String]): Seq[(MillisSinceEpoch, Seq[CrunchMinute])] = {
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queueName)
      val startMinute = group.map(_._1).min
      val queueCrunchMinutes = queueOrder.collect {
        case qn if byQueueName.contains(qn) =>
          val queueMinutes: Seq[CrunchMinute] = byQueueName(qn)
          val allActDesks = queueMinutes.collect { case (CrunchMinute(_, _, _, _, _, _, _, _, _, Some(ad), _, _)) => ad }
          val actDesks = if (allActDesks.isEmpty) None else Option(allActDesks.max)
          val allActWaits = queueMinutes.collect { case (CrunchMinute(_, _, _, _, _, _, _, _, _, _, Some(aw), _)) => aw }
          val actWaits = if (allActWaits.isEmpty) None else Option(allActWaits.max)
          CrunchMinute(
            terminalName = terminalName,
            queueName = qn,
            minute = startMinute,
            paxLoad = queueMinutes.map(_.paxLoad).sum,
            workLoad = queueMinutes.map(_.workLoad).sum,
            deskRec = queueMinutes.map(_.deskRec).max,
            waitTime = queueMinutes.map(_.waitTime).max,
            deployedDesks = Option(queueMinutes.map(_.deployedDesks.getOrElse(0)).max),
            deployedWait = Option(queueMinutes.map(_.deployedWait.getOrElse(0)).max),
            actDesks = actDesks,
            actWait = actWaits
          )
      }
      (startMinute, queueCrunchMinutes)
    })
  }

  def groupStaffMinutesByX(groupSize: Int)(staffMinutes: Seq[(MillisSinceEpoch, StaffMinute)], terminalName: TerminalName): Seq[(MillisSinceEpoch, StaffMinute)] = {
    staffMinutes
      .grouped(groupSize)
      .toList
      .map((milliStaffMinutes: Seq[(MillisSinceEpoch, StaffMinute)]) => {
        val startMinute = milliStaffMinutes.map(_._1).min
        val minutes = milliStaffMinutes.map(_._2)

        val groupedStaffMinute = StaffMinute(
          terminalName,
          startMinute,
          minutes.map(_.shifts).max,
          minutes.map(_.fixedPoints).max,
          minutes.map(_.movements).max
        )

        (startMinute, groupedStaffMinute)
      })
  }

  def terminalMinutesByMinute[T <: Minute](minutes: Set[T], terminalName: TerminalName): Seq[(MillisSinceEpoch, Set[T])] = minutes
    .filter(_.terminalName == terminalName)
    .groupBy(_.minute)
    .toList
    .sortBy(_._1)
}

trait Api {
  def getApplicationVersion(): String

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  def airportConfiguration(): AirportConfig

  def getShifts(pointIntTime: MillisSinceEpoch): Future[String]

  def saveFixedPoints(rawFixedPoints: String): Unit

  def getFixedPoints(pointIntTime: MillisSinceEpoch): Future[String]

  def saveStaffMovements(staffMovements: Seq[StaffMovement]): Unit

  def getStaffMovements(pointIntTime: MillisSinceEpoch): Future[Seq[StaffMovement]]

  def saveStaffTimeSlotsForMonth(timeSlotsForMonth: StaffTimeSlotsForTerminalMonth): Future[Unit]

  def getShiftsForMonth(month: MillisSinceEpoch): Future[String]

  def getCrunchStateForDay(day: MillisSinceEpoch): Future[Option[CrunchState]]

  def getCrunchStateForPointInTime(pointInTime: MillisSinceEpoch): Future[Option[CrunchState]]

  def getCrunchUpdates(sinceMillis: MillisSinceEpoch): Future[Option[CrunchUpdates]]

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastPeriodWithHeadlines]]

  def getUserRoles(): List[String]
}

object ApiSplitsToSplitRatio {

  def queuesFromPaxTypeAndQueue(ptq: List[PaxTypeAndQueue]): Seq[String] = ptq.map {
    case PaxTypeAndQueue(_, q) => q
  }.distinct

  def queueTotals(splits: Map[PaxTypeAndQueue, Int]): Map[QueueName, Int] = splits
    .foldLeft(Map[QueueName, Int]())((map, ptqc) => {
      ptqc match {
        case (PaxTypeAndQueue(_, q), pax) =>
          map + (q -> (map.getOrElse(q, 0) + pax))
      }
    })

  def paxPerQueueUsingBestSplitsAsRatio(flightWithSplits: ApiFlightWithSplits): Option[Map[QueueName, Int]] = {
    flightWithSplits.bestSplits.map(s => flightPaxPerQueueUsingSplitsAsRatio(s, flightWithSplits.apiFlight))
  }

  def flightPaxPerQueueUsingSplitsAsRatio(splits: ApiSplits, flight: Arrival): Map[QueueName, Int] = queueTotals(
    ApiSplitsToSplitRatio.applyPaxSplitsToFlightPax(splits, ArrivalHelper.bestPax(flight))
      .splits
      .map(ptqc => PaxTypeAndQueue(ptqc.passengerType, ptqc.queueType) -> ptqc.paxCount.toInt)
      .toMap
  )

  def applyPaxSplitsToFlightPax(apiSplits: ApiSplits, totalPax: Int): ApiSplits = {
    val splitsSansTransfer = apiSplits.splits.filter(_.queueType != Queues.Transfer)
    val splitsAppliedAsRatio = splitsSansTransfer.map(s => {
      val total = splitsPaxTotal(splitsSansTransfer)
      val paxCountRatio = applyRatio(s, totalPax, total)
      s.copy(paxCount = paxCountRatio)
    })
    apiSplits.copy(
      splitStyle = SplitStyle("Ratio"),
      splits = fudgeRoundingError(splitsAppliedAsRatio, totalPax - splitsPaxTotal(splitsAppliedAsRatio))
    )
  }

  def applyRatio(split: ApiPaxTypeAndQueueCount, totalPax: Int, splitsTotal: Double): Long =
    Math.round(totalPax * (split.paxCount / splitsTotal))

  def fudgeRoundingError(splits: Set[ApiPaxTypeAndQueueCount], diff: Double) =
    splits
      .toList
      .sortBy(_.paxCount)
      .reverse match {
      case head :: tail =>
        (head.copy(paxCount = head.paxCount + diff) :: tail).toSet
      case _ =>
        splits
    }

  def splitsPaxTotal(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toSeq.map(_.paxCount).sum
}
