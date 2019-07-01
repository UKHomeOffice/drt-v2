package drt.shared

import java.util.UUID

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, _}
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.SplitRatiosNs.SplitSources
import ujson.Js.Value
import upickle.Js
import upickle.default.{macroRW, readwriter, ReadWriter => RW}
import upickle.default._

import scala.collection.immutable.SortedMap
import scala.concurrent.Future
import scala.util.matching.Regex

object DeskAndPaxTypeCombinations {
  val egate = "eGate"
  val deskEeaNonMachineReadable = "EEA NMR"
  val deskEea = "EEA"
  val nationalsDeskVisa = "VISA"
  val nationalsDeskNonVisa = "Non-VISA"
}

case class MilliDate(millisSinceEpoch: MillisSinceEpoch) extends Ordered[MilliDate] with WithTimeAccessor {
  override def equals(o: scala.Any): Boolean = o match {
    case MilliDate(millis) => millis == millisSinceEpoch
    case _ => false
  }

  override def compare(that: MilliDate): Int = millisSinceEpoch.compare(that.millisSinceEpoch)

  override def timeValue: MillisSinceEpoch = millisSinceEpoch
}

object MilliDate {
  implicit val rw: RW[MilliDate] = macroRW
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
    case "PaxNumbers" => PaxNumbers
    case "Percentage$" => Percentage
    case "Percentage" => Percentage
    case "Ratio" => Ratio
    case _ => UndefinedSplitStyle
  }

  implicit val splitStyleReadWriter: RW[SplitStyle] =
    readwriter[Js.Value].bimap[SplitStyle](
      feedSource => feedSource.toString,
      (s: Value) => apply(s.str)
    )
}

case object PaxNumbers extends SplitStyle

case object Percentage extends SplitStyle

case object Ratio extends SplitStyle

case object UndefinedSplitStyle extends SplitStyle

case class ApiPaxTypeAndQueueCount(passengerType: PaxType, queueType: String, paxCount: Double, nationalities: Option[Map[String, Double]])

object ApiPaxTypeAndQueueCount {
  implicit val rw: RW[ApiPaxTypeAndQueueCount] = macroRW
}

case class Splits(splits: Set[ApiPaxTypeAndQueueCount], source: String, eventType: Option[String], splitStyle: SplitStyle = PaxNumbers) {
  lazy val totalExcludingTransferPax: Double = Splits.totalExcludingTransferPax(splits)
  lazy val totalPax: Double = Splits.totalPax(splits)
}

object Splits {
  def totalExcludingTransferPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.filter(s => s.queueType != Queues.Transfer).toList.map(_.paxCount).sum

  def totalPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toList.map(_.paxCount).sum

  implicit val rw: RW[Splits] = macroRW
}

case class StaffTimeSlot(terminal: String,
                         start: MillisSinceEpoch,
                         staff: Int,
                         durationMillis: Int
                        )

case class MonthOfShifts(month: MillisSinceEpoch, shifts: ShiftAssignments)

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[Splits], lastUpdated: Option[MillisSinceEpoch] = None) {
  def equals(candidate: ApiFlightWithSplits): Boolean = {
    this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)
  }

  def bestSplits: Option[Splits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType == Option(DqEventCodes.DepartureConfirmed))
    val apiSplitsCi = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages && s.eventType == Option(DqEventCodes.CheckIn))
    val apiSplitsAny = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)
    val predictedSplits = splits.find(s => s.source == SplitSources.PredictedSplitsWithHistoricalEGateAndFTPercentages)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    List(apiSplitsDc, apiSplitsCi, apiSplitsAny, predictedSplits, historicalSplits, terminalSplits).find {
      case Some(_) => true
      case _ => false
    }.flatten
  }

  def apiSplits: Option[Splits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

    List(apiSplitsDc).find {
      case Some(_) => true
      case _ => false
    }.flatten
  }

  def hasPcpPaxIn(start: SDateLike, end: SDateLike): Boolean = apiFlight.hasPcpDuring(start, end)

  lazy val uniqueArrival: UniqueArrival = apiFlight.uniqueArrival
}

object ApiFlightWithSplits {
  implicit val rw: RW[ApiFlightWithSplits] = macroRW
}

trait WithTimeAccessor {
  def timeValue: MillisSinceEpoch
}

case class TQM(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch) extends Ordered[TQM] with WithTimeAccessor {
  override def equals(o: scala.Any): Boolean = o match {
    case TQM(t, q, m) => t == terminalName && q == queueName && m == minute
    case _ => false
  }

  lazy val comparisonString = s"$minute-$queueName-$terminalName"

  override def compare(that: TQM): Int = this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = minute
}

object TQM {
  implicit val rw: RW[TQM] = macroRW

  def apply(crunchMinute: CrunchMinute): TQM = TQM(crunchMinute.terminalName, crunchMinute.queueName, crunchMinute.minute)
}

case class TM(terminalName: TerminalName, minute: MillisSinceEpoch) extends Ordered[TM] with WithTimeAccessor {
  override def equals(o: scala.Any): Boolean = o match {
    case TM(t, m) => t == terminalName && m == minute
    case _ => false
  }

  lazy val comparisonString = s"$minute-$terminalName"

  override def compare(that: TM): Int = this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = minute
}

object TM {
  implicit val rw: RW[TM] = macroRW

  def apply(staffMinute: StaffMinute): TM = TM(staffMinute.terminalName, staffMinute.minute)
}

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

  lazy val carrierCode: String = {
    val bestCode = (IATA, ICAO) match {
      case (iata, _) if iata != "" => iata
      case (_, icao) if icao != "" => icao
      case _ => ""
    }

    bestCode match {
      case Arrival.flightCodeRegex(cc, _, _) => cc
      case _ => ""
    }
  }

  def basicForComparison: Arrival = copy(LastKnownPax = None, PcpTime = None)

  def equals(arrival: Arrival): Boolean = arrival.basicForComparison == basicForComparison

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

  implicit val rw: RW[Arrival] = macroRW
}

sealed trait FeedSource

case object ApiFeedSource extends FeedSource

case object AclFeedSource extends FeedSource

case object ForecastFeedSource extends FeedSource

case object LiveFeedSource extends FeedSource

object FeedSource {
  def feedSources: Set[FeedSource] = Set(ApiFeedSource, AclFeedSource, ForecastFeedSource, LiveFeedSource)

  def apply(name: String): Option[FeedSource] = feedSources.find(fs => fs.toString == name)

  implicit val feedSourceReadWriter: RW[FeedSource] =
    readwriter[Js.Value].bimap[FeedSource](
      feedSource => feedSource.toString,
      (s: Value) => apply(s.str).get
    )
}

case class ArrivalKey(origin: String, voyageNumber: String, scheduled: Long) extends Ordered[ArrivalKey] with WithTimeAccessor {
  lazy val comparisonString = s"$scheduled-$origin-$voyageNumber"

  override def compare(that: ArrivalKey): Int = this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = scheduled
}

object ArrivalKey {
  def apply(arrival: Arrival): ArrivalKey = ArrivalKey(arrival.Origin, arrival.voyageNumberPadded, arrival.Scheduled)
}

case class ArrivalsDiff(toUpdate: SortedMap[ArrivalKey, Arrival], toRemove: Set[Arrival])

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

  def roundToMinute(): SDateLike = {
    val remainder = millisSinceEpoch % 60000
    addMillis(-1 * remainder.toInt)
  }

  def toLocalDateTimeString(): String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d ${getHours()}%02d:${getMinutes()}%02d"

  def toISODateOnly: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d"

  def toHoursAndMinutes(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  def prettyDateTime(): String = f"${getDate()}%02d-${getMonth()}%02d-${getFullYear()} ${getHours()}%02d:${getMinutes()}%02d"

  def prettyTime(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  def hms(): String = f"${getHours()}%02d:${getMinutes()}%02d:${getSeconds()}%02d"

  def getZone(): String

  def getTimeZoneOffsetMillis(): MillisSinceEpoch

  def startOfTheMonth(): SDateLike

  def getLastSunday: SDateLike =
    if (getDayOfWeek() == 7)
      this
    else
      addDays(-1 * getDayOfWeek())

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

object AirportInfo {
  implicit val rw: RW[AirportInfo] = macroRW
}

case class BuildVersion(version: String, requiresReload: Boolean = false)

object BuildVersion {
  implicit val rw: RW[BuildVersion] = macroRW
}

object FlightsApi {

  case class Flights(flights: Seq[Arrival])

  case class FlightsWithSplits(flights: Seq[ApiFlightWithSplits], removals: Set[Arrival]) extends PortStateMinutes {
    def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState] = {
      maybePortState match {
        case None => Option(PortState(flights.toList, List(), List()))
        case Some(portState) =>
          val updatedFlights = flights.foldLeft(portState.flights) {
            case (soFar, updatedFlight) => soFar.updated(updatedFlight.apiFlight.uniqueId, updatedFlight.copy(lastUpdated = Option(now.millisSinceEpoch)))
          }
          val updatedFlightsMinusRemovals = removals.foldLeft(updatedFlights) {
            case (minusRemovals, toRemove) => minusRemovals - toRemove.uniqueId
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

  case class PortStateError(message: String)

  object PortStateError {
    implicit val rw: RW[PortStateError] = macroRW
  }

  case class PortState(flights: Map[Int, ApiFlightWithSplits],
                       crunchMinutes: SortedMap[TQM, CrunchMinute],
                       staffMinutes: SortedMap[TM, StaffMinute]) {
    lazy val latestUpdate: MillisSinceEpoch = {
      val latestFlights = if (flights.nonEmpty) flights.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
      val latestCrunch = if (crunchMinutes.nonEmpty) crunchMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
      val latestStaff = if (staffMinutes.nonEmpty) staffMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
      List(latestFlights, latestCrunch, latestStaff).max
    }

    def window(start: SDateLike, end: SDateLike, portQueues: Map[TerminalName, Seq[QueueName]]): PortState = {
      val roundedStart = start.roundToMinute()
      val roundedEnd = end.roundToMinute().addMinutes(-1)

      val cms = crunchMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
      val sms = staffMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

      PortState(
        flights = flights.filter { case (_, f) => f.apiFlight.hasPcpDuring(roundedStart, roundedEnd) },
        crunchMinutes = cms,
        staffMinutes = sms
      )
    }

    def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: Map[TerminalName, Seq[QueueName]]): PortState = {
      val roundedStart = start.roundToMinute()
      val roundedEnd = end.roundToMinute()

      val cms = crunchMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
      val sms = staffMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

      PortState(
        flights = flights.filter { case (_, f) => f.apiFlight.hasPcpDuring(roundedStart, roundedEnd) && portQueues.contains(f.apiFlight.Terminal) },
        crunchMinutes = cms,
        staffMinutes = sms
      )
    }

    def purgeOlderThanDate(thresholdDate: SDateLike): PortState = {
      val thresholdMillis = thresholdDate.millisSinceEpoch
      val cleansedFlights = flights.filter {
        case (_, ApiFlightWithSplits(arrival, _, _)) => arrival.PcpTime.getOrElse(0L) >= thresholdMillis
      }
      val cleansedCrunchMinutes = crunchMinutes.dropWhile {
        case (TQM(_, _, m), _) => m < thresholdMillis
      }
      val cleansedStaffMinutes = staffMinutes.dropWhile {
        case (TM(_, m), _) => m < thresholdMillis
      }
      PortState(cleansedFlights, cleansedCrunchMinutes, cleansedStaffMinutes)
    }

    def crunchMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: Map[TerminalName, Seq[QueueName]]): SortedMap[TQM, CrunchMinute] = {
      val minTerminal = portQueues.keys.min
      val minQueue = portQueues(minTerminal).min
      val maxTerminal = portQueues.keys.max
      val maxQueue = portQueues(maxTerminal).max
      crunchMinutes
        .from(TQM(minTerminal, minQueue, startMillis))
        .to(TQM(maxTerminal, maxQueue, endMillis))
    }

    def crunchMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: Map[TerminalName, Seq[QueueName]]): SortedMap[TQM, CrunchMinute] =
      crunchMinuteRange(startMillis, endMillis, portQueues)
        .filterKeys { tqm => portQueues.contains(tqm.terminalName) && portQueues(tqm.terminalName).contains(tqm.queueName) }

    def staffMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): SortedMap[TM, StaffMinute] = {
      val minTerminal = terminals.min
      val maxTerminal = terminals.max
      staffMinutes
        .from(TM(minTerminal, startMillis))
        .to(TM(maxTerminal, endMillis))
    }

    def staffMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): SortedMap[TM, StaffMinute] =
      staffMinuteRange(startMillis, endMillis, terminals)
        .filterKeys { tm => terminals.contains(tm.terminalName) }

    def updates(sinceEpoch: MillisSinceEpoch): Option[PortStateUpdates] = {
      val updatedFlights = flights.filter(_._2.apiFlight.PcpTime.isDefined).filter {
        case (_, f) => f.lastUpdated.getOrElse(0L) > sinceEpoch
      }.values.toSet
      val updatedCrunch = crunchMinutes.filter {
        case (_, cm) => cm.lastUpdated.getOrElse(0L) > sinceEpoch
      }.values.toSet
      val updatedStaff = staffMinutes.filter {
        case (_, sm) => sm.lastUpdated.getOrElse(0L) > sinceEpoch
      }.values.toSet
      if (updatedFlights.nonEmpty || updatedCrunch.nonEmpty || updatedStaff.nonEmpty) {
        val flightsLatest = if (updatedFlights.nonEmpty) updatedFlights.map(_.lastUpdated.getOrElse(0L)).max else 0L
        val crunchLatest = if (updatedCrunch.nonEmpty) updatedCrunch.map(_.lastUpdated.getOrElse(0L)).max else 0L
        val staffLatest = if (updatedStaff.nonEmpty) updatedStaff.map(_.lastUpdated.getOrElse(0L)).max else 0L
        val latestUpdate = List(flightsLatest, crunchLatest, staffLatest).max
        Option(PortStateUpdates(latestUpdate, updatedFlights, updatedCrunch, updatedStaff))
      } else None
    }

    def crunchSummary(start: SDateLike, periods: Int, periodSize: Int, terminal: TerminalName, queues: List[String]): Map[Long, Map[String, CrunchMinute]] = {
      val startMillis = start.roundToMinute().millisSinceEpoch
      val endMillis = startMillis + (periods * periodSize * 60000)
      val byPeriod: Map[Long, Map[String, CrunchMinute]] = (startMillis until endMillis by periodSize * 60000)
        .map { periodStart =>
          val queueMinutes = queues
            .map { queue =>
              val slotMinutes = (periodStart until (periodStart + periodSize * 60000) by 60000)
                .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
                .collect { case Some(cm) => cm }
                .toList
              (queue, crunchPeriodSummary(terminal, periodStart, queue, slotMinutes))
            }
            .toMap
          (periodStart, queueMinutes)
        }
        .toMap
      byPeriod
    }

    def staffSummary(start: SDateLike, periods: Int, periodSize: Int, terminal: TerminalName): Map[Long, StaffMinute] = {
      val startMillis = start.roundToMinute().millisSinceEpoch
      val endMillis = startMillis + (periods * periodSize * 60000)
      val byPeriod: Map[Long, StaffMinute] = (startMillis until endMillis by periodSize * 60000)
        .map { periodStart =>
          val slotMinutes = (periodStart until (periodStart + periodSize * 60000) by 60000)
            .map { minute => staffMinutes.get(TM(terminal, minute)) }
            .collect { case Some(cm) => cm }
            .toList
          val queueMinutes = staffPeriodSummary(terminal, periodStart, slotMinutes)
          (periodStart, queueMinutes)
        }
        .toMap
      byPeriod
    }

    def crunchPeriodSummary(terminal: String, periodStart: MillisSinceEpoch, queue: String, slotMinutes: List[CrunchMinute]): CrunchMinute = {
      if (slotMinutes.nonEmpty) CrunchMinute(
        terminalName = terminal,
        queueName = queue,
        minute = periodStart,
        paxLoad = slotMinutes.map(_.paxLoad).sum,
        workLoad = slotMinutes.map(_.workLoad).sum,
        deskRec = slotMinutes.map(_.deskRec).max,
        waitTime = slotMinutes.map(_.waitTime).max,
        deployedDesks = Option(slotMinutes.map(_.deployedDesks.getOrElse(0)).max),
        deployedWait = Option(slotMinutes.map(_.deployedWait.getOrElse(0)).max),
        actDesks = Option(slotMinutes.map(_.actDesks.getOrElse(0)).max),
        actWait = Option(slotMinutes.map(_.actWait.getOrElse(0)).max))
      else CrunchMinute(
        terminalName = terminal,
        queueName = queue,
        minute = periodStart,
        paxLoad = 0,
        workLoad = 0,
        deskRec = 0,
        waitTime = 0,
        deployedDesks = None,
        deployedWait = None,
        actDesks = None,
        actWait = None)
    }

    def staffPeriodSummary(terminal: String, periodStart: MillisSinceEpoch, slotMinutes: List[StaffMinute]): StaffMinute = {
      if (slotMinutes.nonEmpty) StaffMinute(
        terminalName = terminal,
        minute = periodStart,
        shifts = slotMinutes.map(_.shifts).min,
        fixedPoints = slotMinutes.map(_.fixedPoints).max,
        movements = slotMinutes.map(_.movements).max)
      else StaffMinute(
        terminalName = terminal,
        minute = periodStart,
        shifts = 0,
        fixedPoints = 0,
        movements = 0)
    }
  }

  object PortState {
    implicit val rw: ReadWriter[PortState] =
      readwriter[(Map[Int, ApiFlightWithSplits], Map[TQM, CrunchMinute], Map[TM, StaffMinute])]
        .bimap[PortState](ps => portStateToTuple(ps), t => tupleToPortState(t))

    private def tupleToPortState(t: (Map[Int, ApiFlightWithSplits], Map[TQM, CrunchMinute], Map[TM, StaffMinute])): PortState = {
      PortState(t._1, SortedMap[TQM, CrunchMinute]() ++ t._2, SortedMap[TM, StaffMinute]() ++ t._3)
    }

    private def portStateToTuple(ps: PortState): (Map[Int, ApiFlightWithSplits], SortedMap[TQM, CrunchMinute], SortedMap[TM, StaffMinute]) = {
      (ps.flights, ps.crunchMinutes, ps.staffMinutes)
    }

    def apply(flightsWithSplits: List[ApiFlightWithSplits], crunchMinutes: List[CrunchMinute], staffMinutes: List[StaffMinute]): PortState = {
      val flights = flightsWithSplits.map(fws => (fws.apiFlight.uniqueId, fws)).toMap
      val cms = SortedMap[TQM, CrunchMinute]() ++ crunchMinutes.map(cm => (TQM(cm), cm))
      val sms = SortedMap[TM, StaffMinute]() ++ staffMinutes.map(sm => (TM(sm), sm))
      PortState(flights, cms, sms)
    }

    val empty: PortState = PortState(Map[Int, ApiFlightWithSplits](), SortedMap[TQM, CrunchMinute](), SortedMap[TM, StaffMinute]())
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

    implicit val rw: RW[StaffMinute] = macroRW
  }

  case class StaffMinutes(minutes: Seq[StaffMinute]) extends PortStateMinutes {
    def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState] = {
      maybePortState match {
        case None => Option(PortState(List(), List(), minutes.toList))
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

    implicit val rw: RW[StaffMinutes] = macroRW
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

    implicit val rw: RW[CrunchMinute] = macroRW
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


  case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])

  case class PortStateUpdates(latest: MillisSinceEpoch, flights: Set[ApiFlightWithSplits], minutes: Set[CrunchMinute], staff: Set[StaffMinute])

  object PortStateUpdates {
    implicit val rw: RW[PortStateUpdates] = macroRW
  }

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  case class ForecastPeriod(days: Map[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  case class ForecastHeadlineFigures(queueDayHeadlines: Seq[QueueHeadline])

  case class QueueHeadline(day: MillisSinceEpoch, queue: QueueName, paxNos: Int, workload: Int)

  def groupCrunchMinutesByX(groupSize: Int)(crunchMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])], terminalName: TerminalName, queueOrder: List[String]): Seq[(MillisSinceEpoch, Seq[CrunchMinute])] = {
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queueName)
      val startMinute = group.map(_._1).min
      val queueCrunchMinutes = queueOrder.collect {
        case qn if byQueueName.contains(qn) =>
          val queueMinutes: Seq[CrunchMinute] = byQueueName(qn)
          val allActDesks = queueMinutes.collect { case CrunchMinute(_, _, _, _, _, _, _, _, _, Some(ad), _, _) => ad }
          val actDesks = if (allActDesks.isEmpty) None else Option(allActDesks.max)
          val allActWaits = queueMinutes.collect { case CrunchMinute(_, _, _, _, _, _, _, _, _, _, Some(aw), _) => aw }
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

  def terminalMinutesByMinute[T <: Minute](minutes: List[T], terminalName: TerminalName): Seq[(MillisSinceEpoch, List[T])] = minutes
    .filter(_.terminalName == terminalName)
    .groupBy(_.minute)
    .toList
    .sortBy(_._1)

}

trait Api {

  def getShifts(maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def addStaffMovements(movementsToAdd: Seq[StaffMovement]): Unit

  def removeStaffMovements(movementsToRemove: UUID): Unit

  def getStaffMovements(maybePointInTime: Option[MillisSinceEpoch]): Future[Seq[StaffMovement]]

  def getShiftsForMonth(month: MillisSinceEpoch, terminalName: TerminalName): Future[ShiftAssignments]

  def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: TerminalName): Future[Option[ForecastPeriodWithHeadlines]]

  def getLoggedInUser(): LoggedInUser

  def getKeyCloakUsers(): Future[List[KeyCloakUser]]

  def getKeyCloakGroups(): Future[List[KeyCloakGroup]]

  def getKeyCloakUserGroups(userId: UUID): Future[Set[KeyCloakGroup]]

  def addUserToGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def removeUserFromGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def getShowAlertModalDialog(): Boolean
}


