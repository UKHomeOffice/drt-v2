package drt.shared

import java.util.UUID

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, _}
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
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

case class MilliDate(millisSinceEpoch: MillisSinceEpoch) extends Ordered[MilliDate] {
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

case class Splits(splits: Set[ApiPaxTypeAndQueueCount], source: String, eventType: Option[String], splitStyle: SplitStyle = PaxNumbers) {
  lazy val totalExcludingTransferPax: Double = Splits.totalExcludingTransferPax(splits)
  lazy val totalPax: Double = Splits.totalPax(splits)
}

case class StaffTimeSlot(terminal: String,
                         start: MillisSinceEpoch,
                         staff: Int,
                         durationMillis: Int)

case class MonthOfShifts(month: MillisSinceEpoch, shifts: ShiftAssignments)

object Splits {
  def totalExcludingTransferPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.filter(s => s.queueType != Queues.Transfer).toList.map(_.paxCount).sum

  def totalPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toList.map(_.paxCount).sum
}

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[Splits], lastUpdated: Option[MillisSinceEpoch] = None) {
  def equals(candidate: ApiFlightWithSplits): Boolean = {
    this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)
  }

  def bestSplits: Option[Splits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.CheckIn))
    val predictedSplits = splits.find(s => s.source == SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    List(apiSplitsDc, apiSplitsCi, predictedSplits, historicalSplits, terminalSplits).find {
      case Some(_) => true
      case _ => false
    }.flatten
  }

  def apiSplits: Option[Splits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType.contains(DqEventCodes.CheckIn))

    List(apiSplitsDc, apiSplitsCi).find {
      case Some(_) => true
      case _ => false
    }.flatten
  }

  def hasPcpPaxIn(start: SDateLike, end: SDateLike): Boolean = apiFlight.hasPcpDuring(start, end)

  lazy val uniqueArrival: UniqueArrival = apiFlight.uniqueArrival
}

case class TQM(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch)

case class TM(terminalName: TerminalName, minute: MillisSinceEpoch)

case class UniqueArrival(number: Int, terminalName: TerminalName, scheduled: MillisSinceEpoch) {
  lazy val uniqueId: Int = uniqueStr.hashCode
  lazy val uniqueStr: String = s"$terminalName$scheduled$number"
}

object MinuteHelper {
  def key(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch): TQM = TQM(terminalName, queueName, minute)

  def key(terminalName: TerminalName, minute: MillisSinceEpoch): TM = TM(terminalName, minute)
}

case class FlightsNotReady()

case class Arrival(
                    Operator: Option[String],
                    Status: String,
                    Estimated: Option[MillisSinceEpoch],
                    Actual: Option[MillisSinceEpoch],
                    EstimatedChox: Option[MillisSinceEpoch],
                    ActualChox: Option[MillisSinceEpoch],
                    Gate: Option[String],
                    Stand: Option[String],
                    MaxPax: Option[Int],
                    ActPax: Option[Int],
                    TranPax: Option[Int],
                    RunwayID: Option[String],
                    BaggageReclaimId: Option[String],
                    FlightID: Option[Int],
                    AirportID: String,
                    Terminal: String,
                    rawICAO: String,
                    rawIATA: String,
                    Origin: String,
                    Scheduled: MillisSinceEpoch,
                    PcpTime: Option[MillisSinceEpoch],
                    FeedSources: Set[FeedSource],
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

  def voyageNumberPadded: String = {
    val number = FlightParsing.parseIataToCarrierCodeVoyageNumber(IATA)
    ArrivalHelper.padTo4Digits(number.map(_._2).getOrElse("-"))
  }

  lazy val manifestKey: Int = s"$voyageNumberPadded-${this.Scheduled}".hashCode

  lazy val uniqueId: Int = uniqueStr.hashCode
  lazy val uniqueStr: String = s"$Terminal$Scheduled$flightNumber"

  def hasPcpDuring(start: SDateLike, end: SDateLike): Boolean = {
    val firstPcpMilli = PcpTime.getOrElse(0L)
    val lastPcpMilli = firstPcpMilli + millisToDisembark(ActPax.getOrElse(0))
    val firstInRange = start.millisSinceEpoch <= firstPcpMilli && firstPcpMilli <= end.millisSinceEpoch
    val lastInRange = start.millisSinceEpoch <= lastPcpMilli && lastPcpMilli <= end.millisSinceEpoch
    firstInRange || lastInRange
  }

  def millisToDisembark(pax: Int): Long = {
    val minutesToDisembark = (pax.toDouble / 20).ceil
    val oneMinuteInMillis = 60 * 1000
    (minutesToDisembark * oneMinuteInMillis).toLong
  }

  lazy val uniqueArrival: UniqueArrival = UniqueArrival(flightNumber, Terminal, Scheduled)
}

object Arrival {
  val flightCodeRegex: Regex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

  def summaryString(arrival: Arrival): TerminalName = arrival.AirportID + "/" + arrival.Terminal + "@" + arrival.Scheduled + "!" + arrival.IATA

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

sealed trait FeedSource

case object ApiFeedSource extends FeedSource

case object AclFeedSource extends FeedSource

case object ForecastFeedSource extends FeedSource

case object LiveFeedSource extends FeedSource

object FeedSource {
  def feedSources: Set[FeedSource] = Set(ApiFeedSource, AclFeedSource, ForecastFeedSource, LiveFeedSource)

  def apply(name: String): Option[FeedSource] = feedSources.find(fs => fs.toString == name)
}

case class ArrivalsDiff(toUpdate: Set[Arrival], toRemove: Set[Int])

trait SDateLike {

  def ddMMyyString: String = f"${getDate}%02d/${getMonth}%02d/${getFullYear - 2000}%02d"

  val months = List(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  )

  /**
    * Days of the week 1 to 7 (Monday is 1)
    *
    * @return
    */
  def getDayOfWeek(): Int

  def getFullYear(): Int

  def getMonth(): Int

  def getMonthString(): String = months(getMonth() - 1)

  def getDate(): Int

  def getHours(): Int

  def getMinutes(): Int

  def getSeconds(): Int

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

  def hms(): String = f"${getHours()}%02d:${getMinutes()}%02d:${getSeconds()}%02d"

  def getZone(): String

  def getTimeZoneOffsetMillis(): MillisSinceEpoch

  def startOfTheMonth(): SDateLike

  override def toString: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02dT${getHours()}%02d${getMinutes()}%02d"

  override def equals(obj: scala.Any): Boolean = {
    obj match {
      case d: SDateLike =>
        d.millisSinceEpoch == millisSinceEpoch
      case _ => false
    }
  }

  def compare(that: SDateLike): Int = millisSinceEpoch.compare(that.millisSinceEpoch)

  def <=(compareTo: SDateLike): Boolean = millisSinceEpoch <= compareTo.millisSinceEpoch
}

trait PortStateMinutes {
  def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState]
}

object CrunchResult {
  def empty = CrunchResult(0, 0, Vector[Int](), List())
}


case class CrunchResult(firstTimeMillis: MillisSinceEpoch,
                        intervalMillis: MillisSinceEpoch,
                        recommendedDesks: IndexedSeq[Int],
                        waitTimes: Seq[Int])

case class AirportInfo(airportName: String, city: String, country: String, code: String)

object FlightsApi {

  case class Flights(flights: Seq[Arrival])

  case class FlightsWithSplits(flights: Seq[ApiFlightWithSplits], removals: Set[Int]) extends PortStateMinutes {
    def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState] = {
      maybePortState match {
        case None => Option(PortState(flights.map(f => (f.apiFlight.uniqueId, f)).toMap, Map(), Map()))
        case Some(portState) =>
          val updatedFlights = flights.foldLeft(portState.flights) {
            case (soFar, updatedFlight) => soFar.updated(updatedFlight.apiFlight.uniqueId, updatedFlight.copy(lastUpdated = Option(now.millisSinceEpoch)))
          }
          val updatedFlightsMinusRemovals = removals.foldLeft(updatedFlights) {
            case (minusRemovals, toRemove) => minusRemovals - toRemove
          }
          Option(portState.copy(flights = updatedFlightsMinusRemovals))
      }
    }
  }

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

object CrunchApi {
  type MillisSinceEpoch = Long

  case class CrunchStateError(message: String)

  case class CrunchState(flights: Set[ApiFlightWithSplits],
                         crunchMinutes: Set[CrunchMinute],
                         staffMinutes: Set[StaffMinute]) {
    def window(start: SDateLike, end: SDateLike): CrunchState = {
      val windowedFlights = flights.filter(f => f.apiFlight.hasPcpDuring(start, end))
      val windowedCrunchMinutes = crunchMinutes.filter(cm => start.millisSinceEpoch <= cm.minute && cm.minute <= end.millisSinceEpoch)
      val windowsStaffMinutes = staffMinutes.filter(sm => start.millisSinceEpoch <= sm.minute && sm.minute <= end.millisSinceEpoch)
      CrunchState(windowedFlights, windowedCrunchMinutes, windowsStaffMinutes)
    }

    def window(start: SDateLike, end: SDateLike, terminalName: TerminalName): CrunchState = {
      val windowedFlights = flights.filter(f => f.apiFlight.hasPcpDuring(start, end) && f.apiFlight.Terminal == terminalName)
      val windowedCrunchMinutes = crunchMinutes.filter(cm => start.millisSinceEpoch <= cm.minute && cm.minute <= end.millisSinceEpoch && cm.terminalName == terminalName)
      val windowsStaffMinutes = staffMinutes.filter(sm => start.millisSinceEpoch <= sm.minute && sm.minute <= end.millisSinceEpoch && sm.terminalName == terminalName)
      CrunchState(windowedFlights, windowedCrunchMinutes, windowsStaffMinutes)
    }

    def isEmpty: Boolean = flights.isEmpty && crunchMinutes.isEmpty && staffMinutes.isEmpty
  }

  case class PortState(flights: Map[Int, ApiFlightWithSplits],
                       crunchMinutes: Map[TQM, CrunchMinute],
                       staffMinutes: Map[TM, StaffMinute]) {
    def window(start: SDateLike, end: SDateLike): PortState = {
      val windowedFlights = flights.filter {
        case (_, f) => f.apiFlight.hasPcpDuring(start, end)
      }
      val windowedCrunchMinutes = crunchMinutes.filter {
        case (_, cm) => start.millisSinceEpoch <= cm.minute && cm.minute <= end.millisSinceEpoch
      }
      val windowsStaffMinutes = staffMinutes.filter {
        case (_, sm) => start.millisSinceEpoch <= sm.minute && sm.minute <= end.millisSinceEpoch
      }
      PortState(windowedFlights, windowedCrunchMinutes, windowsStaffMinutes)
    }
  }

  trait Minute {
    val minute: MillisSinceEpoch
    val lastUpdated: Option[MillisSinceEpoch]
    val terminalName: TerminalName
  }

  trait TerminalQueueMinute {
    val terminalName: TerminalName
    val queueName: QueueName
    val minute: MillisSinceEpoch
  }

  trait TerminalMinute {
    val terminalName: TerminalName
    val minute: MillisSinceEpoch
  }

  case class StaffMinute(terminalName: TerminalName,
                         minute: MillisSinceEpoch,
                         shifts: Int,
                         fixedPoints: Int,
                         movements: Int,
                         lastUpdated: Option[MillisSinceEpoch] = None) extends Minute with TerminalMinute {
    def equals(candidate: StaffMinute): Boolean =
      this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: TM = MinuteHelper.key(terminalName, minute)
    lazy val available: Int = shifts + movements match {
      case sa if sa >= 0 => sa
      case _ => 0
    }
    lazy val availableAtPcp: Int = {
      shifts - fixedPoints + movements match {
        case sa if sa >= 0 => sa
        case _ => 0
      }
    }
  }

  object StaffMinute {
    def empty = StaffMinute("", 0L, 0, 0, 0, None)
  }

  case class StaffMinutes(minutes: Seq[StaffMinute]) extends PortStateMinutes {
    def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState] = {
      maybePortState match {
        case None => Option(PortState(Map(), Map(), minutes.map(sm => (sm.key, sm)).toMap))
        case Some(portState) =>
          val updatedSms = minutes.foldLeft(portState.staffMinutes) {
            case (soFar, updatedSm) => soFar.updated(updatedSm.key, updatedSm.copy(lastUpdated = Option(now.millisSinceEpoch)))
          }

          Option(portState.copy(staffMinutes = updatedSms))
      }
    }
  }

  object StaffMinutes {
    def apply(minutesByKey: Map[TM, StaffMinute]): StaffMinutes = StaffMinutes(minutesByKey.values.toSeq)
  }

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

    lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)
  }

  trait DeskRecMinuteLike {
    val terminalName: TerminalName
    val queueName: QueueName
    val minute: MillisSinceEpoch
    val paxLoad: Double
    val workLoad: Double
    val deskRec: Int
    val waitTime: Int
  }

  trait SimulationMinuteLike {
    val terminalName: TerminalName
    val queueName: QueueName
    val minute: MillisSinceEpoch
    val desks: Int
    val waitTime: Int
  }

  object CrunchMinute {
    def apply(drm: DeskRecMinuteLike): CrunchMinute = CrunchMinute(
      terminalName = drm.terminalName,
      queueName = drm.queueName,
      minute = drm.minute,
      paxLoad = drm.paxLoad,
      workLoad = drm.workLoad,
      deskRec = drm.deskRec,
      waitTime = drm.waitTime)

    def apply(sm: SimulationMinuteLike): CrunchMinute = CrunchMinute(
      terminalName = sm.terminalName,
      queueName = sm.queueName,
      minute = sm.minute,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      deployedDesks = Option(sm.desks),
      deployedWait = Option(sm.waitTime))
  }

  case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])

  case class CrunchUpdates(latest: MillisSinceEpoch, flights: Set[ApiFlightWithSplits], minutes: Set[CrunchMinute], staff: Set[StaffMinute])

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  case class ForecastPeriod(days: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  case class ForecastHeadlineFigures(queueDayHeadlines: Set[QueueHeadline])

  case class QueueHeadline(day: MillisSinceEpoch, queue: QueueName, paxNos: Int, workload: Int)

  def staffByTimeSlot(slotSize: Int)(staffMinutes: Set[StaffMinute], terminalName: TerminalName): Map[MillisSinceEpoch, Int] = {
    staffMinutes
      .filter(_.terminalName == terminalName)
      .toList
      .sortBy(_.minute)
      .grouped(slotSize)
      .toList
      .map(slot => {
        slot.map(_.minute).min -> slot.map(_.shifts).min
      }).toMap
  }

  def fixedPointsByTimeSlot(slotSize: Int)(staffMinutes: Set[StaffMinute], terminalName: TerminalName): Map[MillisSinceEpoch, Int] = {
    staffMinutes
      .filter(_.terminalName == terminalName)
      .toList
      .sortBy(_.minute)
      .grouped(slotSize)
      .toList
      .map(slot => {
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
      .filter(_._2.terminalName == terminalName)
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

  def getAlerts(createdAfter: MillisSinceEpoch): Future[Seq[Alert]]

  def deleteAllAlerts(): Unit

  def saveAlert(alert: Alert): Unit

  def airportInfoByAirportCode(code: String): Future[Option[AirportInfo]]

  def airportInfosByAirportCodes(codes: Set[String]): Future[Map[String, AirportInfo]]

  def airportConfiguration(): AirportConfig

  def getShifts(maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def saveFixedPoints(fixedPoints: FixedPointAssignments): Unit

  def getFixedPoints(maybePointIntTime: Option[MillisSinceEpoch]): Future[FixedPointAssignments]

  def addStaffMovements(movementsToAdd: Seq[StaffMovement]): Unit

  def removeStaffMovements(movementsToRemove: UUID): Unit

  def getStaffMovements(maybePointInTime: Option[MillisSinceEpoch]): Future[Seq[StaffMovement]]

  def getShiftsForMonth(month: MillisSinceEpoch, terminalName: TerminalName): Future[ShiftAssignments]

  def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit

  def getCrunchStateForDay(day: MillisSinceEpoch): Future[Either[CrunchStateError, Option[CrunchState]]]

  def getCrunchStateForPointInTime(pointInTime: MillisSinceEpoch): Future[Either[CrunchStateError, Option[CrunchState]]]

  def getCrunchUpdates(sinceMillis: MillisSinceEpoch, windowStartMillis: MillisSinceEpoch, windowEndMillis: MillisSinceEpoch): Future[Either[CrunchStateError, Option[CrunchUpdates]]]

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastPeriodWithHeadlines]]

  def isLoggedIn(): Boolean

  def getLoggedInUser(): LoggedInUser

  def getFeedStatuses(): Future[Seq[FeedStatuses]]

  def getKeyCloakUsers(): Future[List[KeyCloakUser]]

  def getKeyCloakGroups(): Future[List[KeyCloakGroup]]

  def getKeyCloakUserGroups(userId: UUID): Future[Set[KeyCloakGroup]]

  def addUserToGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def removeUserFromGroups(userId: UUID, groups: Set[String]): Future[Unit]
}
