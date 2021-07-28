package drt.shared

import drt.shared.CrunchApi._
import drt.shared.DataUpdates.{FlightUpdates, MinuteUpdates}
import drt.shared.EventTypes.{CI, DC, InvalidEventType}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.MilliTimes.{oneDayMillis, oneMinuteMillis}
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.Terminals.Terminal
import drt.shared.api.{Arrival, FlightCodeSuffix}
import drt.shared.dates.{LocalDate, UtcDate}
import ujson.Js.Value
import uk.gov.homeoffice.drt.Urls
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.Role
import upickle.default._

import java.lang.Math.round
import java.util.UUID
import scala.collection.immutable.{Map => IMap, SortedMap => ISortedMap}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object DeskAndPaxTypeCombinations {
  val egate = "eGate"
  val deskEeaNonMachineReadable = "EEA NMR"
  val deskEea = "EEA"
  val nationalsDeskVisa = "VISA"
  val nationalsDeskNonVisa = "Non-VISA"
}

case class MilliDate(_millisSinceEpoch: MillisSinceEpoch) extends Ordered[MilliDate] with WithTimeAccessor {
  lazy val secondsOffset: MillisSinceEpoch = _millisSinceEpoch % 60000

  lazy val millisSinceEpoch: MillisSinceEpoch = _millisSinceEpoch - secondsOffset

  override def compare(that: MilliDate): Int = _millisSinceEpoch.compare(that._millisSinceEpoch)

  override def timeValue: MillisSinceEpoch = _millisSinceEpoch
}

object MilliDate {
  implicit val rw: ReadWriter[MilliDate] = macroRW

  def atTime: MillisSinceEpoch => MilliDate = (time: MillisSinceEpoch) => MilliDate(time)
}

case class FlightCode(
                       carrierCode: CarrierCode,
                       voyageNumberLike: VoyageNumberLike,
                       maybeFlightCodeSuffix: Option[FlightCodeSuffix]) {

  val suffixString: String = maybeFlightCodeSuffix.map(_.suffix).getOrElse("")

  override def toString = s"${carrierCode}${voyageNumberLike.toPaddedString}$suffixString"

}

object FlightCode {
  val iataRe: Regex = "^([A-Z0-9]{2}?)([0-9]{1,4})([A-Z]*)$".r
  val icaoRe: Regex = "^([A-Z]{2,3}?)([0-9]{1,4})([A-Z]*)$".r

  def flightCodeToParts(code: String): (CarrierCode, VoyageNumberLike, Option[FlightCodeSuffix]) = code match {
    case iataRe(cc, vn, suffix) => stringsToComponents(cc, vn, suffix)
    case icaoRe(cc, vn, suffix) => stringsToComponents(cc, vn, suffix)
    case _ => (CarrierCode(""), VoyageNumber(0), None)
  }

  def apply(rawIATA: String, rawICAO: String): FlightCode = {
    FlightCode(bestCode(rawIATA, rawICAO))
  }

  def apply(code: String): FlightCode = {
    val (carrierCode: CarrierCode, voyageNumber: VoyageNumber, maybeSuffix: Option[FlightCodeSuffix]) = {

      FlightCode.flightCodeToParts(code)
    }
    FlightCode(carrierCode, voyageNumber, maybeSuffix)
  }

  def bestCode(rawIATA: String, rawICAO: String): String = (rawIATA, rawICAO) match {
    case (iata, _) if iata != "" => iata
    case (_, icao) if icao != "" => icao
    case _ => ""
  }

  private def stringsToComponents(cc: String,
                                  vn: String,
                                  suffix: String): (CarrierCode, VoyageNumberLike, Option[FlightCodeSuffix]) = {
    val carrierCode = CarrierCode(cc)
    val voyageNumber = VoyageNumber(vn)
    val arrivalSuffix = if (suffix.nonEmpty) Option(FlightCodeSuffix(suffix)) else None
    (carrierCode, voyageNumber, arrivalSuffix)
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

  implicit val splitStyleReadWriter: ReadWriter[SplitStyle] =
    readwriter[Value].bimap[SplitStyle](
      feedSource => feedSource.toString,
      (s: Value) => apply(s.str)
    )
}

case object PaxNumbers extends SplitStyle

case object Percentage extends SplitStyle

case object Ratio extends SplitStyle

case object UndefinedSplitStyle extends SplitStyle

case class Nationality(code: String) {
  override def toString: String = code
}

object Nationality {
  implicit val rw: ReadWriter[Nationality] = macroRW
}

case class PaxAge(years: Int) {
  def isUnder(age: Int): Boolean = years < age

  override def toString: String = s"$years"
}

object PaxAge {
  implicit val rw: ReadWriter[PaxAge] = macroRW
}

case class ApiPaxTypeAndQueueCount(
                                    passengerType: PaxType,
                                    queueType: Queue,
                                    paxCount: Double,
                                    nationalities: Option[IMap[Nationality, Double]],
                                    ages: Option[IMap[PaxAge, Double]]
                                  ) {
  val paxTypeAndQueue: PaxTypeAndQueue = PaxTypeAndQueue(passengerType, queueType)
}

object ApiPaxTypeAndQueueCount {
  implicit val rw: ReadWriter[ApiPaxTypeAndQueueCount] = macroRW
}

sealed trait EventType extends ClassNameForToString

object EventType {
  implicit val rw: ReadWriter[EventType] = macroRW

  def apply(eventType: String): EventType = eventType match {
    case "DC" => DC
    case "CI" => CI
    case _ => InvalidEventType
  }
}

object EventTypes {

  object DC extends EventType

  object CI extends EventType

  object InvalidEventType extends EventType

}


case class Splits(splits: Set[ApiPaxTypeAndQueueCount],
                  source: SplitSource,
                  maybeEventType: Option[EventType],
                  splitStyle: SplitStyle = PaxNumbers) {
  lazy val totalExcludingTransferPax: Double = Splits.totalExcludingTransferPax(splits)
  lazy val totalPax: Double = Splits.totalPax(splits)
}

object Splits {
  def totalExcludingTransferPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.filter(s => s.queueType != Queues.Transfer).toList.map(_.paxCount).sum

  def totalPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toList.map(s => {
    s.paxCount
  }).sum

  implicit val rw: ReadWriter[Splits] = macroRW
}

case class StaffTimeSlot(terminal: Terminal,
                         start: MillisSinceEpoch,
                         staff: Int,
                         durationMillis: Int)

case class MonthOfShifts(month: MillisSinceEpoch, shifts: ShiftAssignments)

trait WithLastUpdated {
  def lastUpdated: Option[MillisSinceEpoch]
}

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[Splits], lastUpdated: Option[MillisSinceEpoch] = None)
  extends WithUnique[UniqueArrival]
    with WithLastUpdated {

  def totalPaxFromApi: Option[Int] = splits.collectFirst {
    case splits if splits.source == ApiSplitsWithHistoricalEGateAndFTPercentages =>
      Math.round(splits.totalPax).toInt
  }

  def totalPaxFromApiExcludingTransfer: Option[Int] =
    splits.collectFirst { case splits if splits.source == ApiSplitsWithHistoricalEGateAndFTPercentages =>
      Math.round(splits.totalExcludingTransferPax).toInt
    }

  def pcpPaxEstimate: Int =
    totalPaxFromApiExcludingTransfer match {
      case Some(apiTotal) if hasValidApi => apiTotal
      case _ => apiFlight.bestPcpPaxEstimate
    }

  def totalPax: Option[Int] =
    if (hasValidApi) totalPaxFromApi
    else apiFlight.ActPax

  def equals(candidate: ApiFlightWithSplits): Boolean =
    this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

  def bestSplits: Option[Splits] = {
    val apiSplitsDc = splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)
    val scenarioSplits = splits.find(s => s.source == SplitSources.ScenarioSimulationSplits)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    val apiSplits: List[Option[Splits]] = if (hasValidApi) List(apiSplitsDc) else List(scenarioSplits)

    val splitsForConsideration: List[Option[Splits]] = apiSplits ::: List(historicalSplits, terminalSplits)

    splitsForConsideration.find {
      case Some(_) => true
      case _ => false
    }.flatten
  }

  val hasApi: Boolean = splits.exists(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)

  def hasValidApi: Boolean = {
    val maybeApiSplits = splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages)
    val hasLiveSource = apiFlight.FeedSources.contains(LiveFeedSource)
    val hasSimulationSource = apiFlight.FeedSources.contains(ScenarioSimulationSource)
    (maybeApiSplits, hasLiveSource, hasSimulationSource) match {
      case (Some(_), _, true) => true
      case (Some(_), false, _) => true
      case (Some(api), true, _) if isWithinThreshold(api) => true
      case _ => false
    }
  }

  def isWithinThreshold(apiSplits: Splits): Boolean = {
    val apiPaxNo = apiSplits.totalExcludingTransferPax
    val threshold: Double = 0.05
    val portDirectPax: Double = apiFlight.ActPax.getOrElse(0) - apiFlight.TranPax.getOrElse(0)
    apiPaxNo != 0 && Math.abs(apiPaxNo - portDirectPax) / apiPaxNo < threshold
  }

  def hasPcpPaxIn(start: SDateLike, end: SDateLike): Boolean = apiFlight.hasPcpDuring(start, end)

  override val unique: UniqueArrival = apiFlight.unique
}

object ApiFlightWithSplits {
  implicit val rw: ReadWriter[ApiFlightWithSplits] = macroRW

  def fromArrival(arrival: Arrival): ApiFlightWithSplits = ApiFlightWithSplits(arrival, Set())
}

trait WithTimeAccessor {
  def timeValue: MillisSinceEpoch
}

trait WithUnique[I] {
  def unique: I
}

trait WithTerminal[A] extends Ordered[A] {
  def terminal: Terminal
}

sealed trait UniqueArrivalLike {
  val number: Int
  val terminal: Terminal
  val scheduled: MillisSinceEpoch
}

case class LegacyUniqueArrival(number: Int, terminal: Terminal, scheduled: MillisSinceEpoch) extends UniqueArrivalLike

object LegacyUniqueArrival {
  def apply(number: Int,
            terminalName: String,
            scheduled: MillisSinceEpoch): LegacyUniqueArrival = LegacyUniqueArrival(number, Terminal(terminalName), scheduled)
}

case class UniqueArrival(number: Int, terminal: Terminal, scheduled: MillisSinceEpoch, origin: PortCode)
  extends WithTimeAccessor
    with WithTerminal[UniqueArrival]
    with UniqueArrivalLike {

  lazy val legacyUniqueArrival: LegacyUniqueArrival = LegacyUniqueArrival(number, terminal, scheduled)

  override def compare(that: UniqueArrival): Int =
    scheduled.compare(that.scheduled) match {
      case 0 => terminal.compare(that.terminal) match {
        case 0 => number.compare(that.number) match {
          case 0 => origin.iata.compare(that.origin.iata)
          case c => c
        }
        case c => c
      }
      case c => c
    }

  override def timeValue: MillisSinceEpoch = scheduled

  def legacyUniqueId: Int = s"$terminal$scheduled$number".hashCode

  val equalWithinScheduledWindow: (UniqueArrival, Int) => Boolean = (searchKey, windowMillis) =>
    searchKey.number == this.number && searchKey.terminal == this.terminal && Math.abs(searchKey.scheduled - this.scheduled) <= windowMillis

  def equalsLegacy(lua: LegacyUniqueArrival): Boolean =
    lua.number == number && lua.scheduled == scheduled && lua.terminal == terminal
}

object UniqueArrival {
  implicit val rw: ReadWriter[UniqueArrival] = macroRW

  def apply(arrival: Arrival): UniqueArrival = UniqueArrival(arrival.VoyageNumber.numeric, arrival.Terminal, arrival.Scheduled, arrival.Origin)

  def apply(number: Int,
            terminalName: String,
            scheduled: MillisSinceEpoch,
            origin: String): UniqueArrival = UniqueArrival(number, Terminal(terminalName), scheduled, PortCode(origin))

  def atTime: MillisSinceEpoch => UniqueArrival = (time: MillisSinceEpoch) => UniqueArrival(0, "", time, "")
}

case class CodeShareKeyOrderedBySchedule(scheduled: Long,
                                         terminal: Terminal,
                                         origin: PortCode) extends Ordered[CodeShareKeyOrderedBySchedule] with WithTimeAccessor {
  override def compare(that: CodeShareKeyOrderedBySchedule): Int = scheduled.compare(that.scheduled) match {
    case 0 => terminal.compare(that.terminal) match {
      case 0 => origin.compare(that.origin)
      case c => c
    }
    case c => c
  }

  override def timeValue: MillisSinceEpoch = scheduled
}

object CodeShareKeyOrderedBySchedule {
  def apply(arrival: Arrival): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(arrival.Scheduled, arrival.Terminal, arrival.Origin)

  def apply(fws: ApiFlightWithSplits): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(fws.apiFlight.Scheduled, fws.apiFlight.Terminal, fws.apiFlight.Origin)

  def apply(scheduled: Long,
            terminalName: String,
            origin: PortCode): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(scheduled, Terminal(terminalName), origin)

  def atTime: MillisSinceEpoch => CodeShareKeyOrderedBySchedule = (millis: MillisSinceEpoch) => CodeShareKeyOrderedBySchedule(millis, "", PortCode(""))
}

case class CodeShareKeyOrderedByDupes[A](scheduled: Long,
                                         terminal: Terminal,
                                         origin: PortCode,
                                         arrivalKeys: Set[A]) extends Ordered[CodeShareKeyOrderedByDupes[A]] {
  private val dupeCountReversed: Int = 100 - arrivalKeys.size

  override def compare(that: CodeShareKeyOrderedByDupes[A]): Int = dupeCountReversed.compare(that.dupeCountReversed) match {
    case 0 => scheduled.compare(that.scheduled) match {
      case 0 => terminal.compare(that.terminal) match {
        case 0 => origin.compare(that.origin)
        case c => c
      }
      case c => c
    }
    case c => c
  }
}

object MinuteHelper {
  def key(terminalName: Terminal, queue: Queue, minute: MillisSinceEpoch): TQM = TQM(terminalName, queue, minute)

  def key(terminalName: Terminal, minute: MillisSinceEpoch): TM = TM(terminalName, minute)
}

case class FlightsNotReady()

sealed trait VoyageNumberLike {
  def numeric: Int

  def toPaddedString: String
}

case class VoyageNumber(numeric: Int) extends VoyageNumberLike with Ordered[VoyageNumber] {
  override def toString: String = numeric.toString

  def toPaddedString: String = {
    val string = numeric.toString
    val prefix = string.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + string
  }

  override def compare(that: VoyageNumber): Int = numeric.compare(that.numeric)
}

case class InvalidVoyageNumber(exception: Throwable) extends VoyageNumberLike {
  override def toString: String = "invalid"

  override def toPaddedString: String = toString

  override def numeric: Int = 0
}

case object VoyageNumber {
  implicit val rw: ReadWriter[VoyageNumber] = macroRW

  def apply(string: String): VoyageNumberLike = Try(string.toInt) match {
    case Success(value) => VoyageNumber(value)
    case Failure(exception) => InvalidVoyageNumber(exception)
  }
}

case class Operator(code: String)

case class ArrivalStatus(description: String) {
  override def toString: String = description
}

case class FeedSourceArrival(feedSource: FeedSource, arrival: Arrival)

object FeedSourceArrival {
  implicit val rw: ReadWriter[FeedSourceArrival] = macroRW
}

trait FeedSource {
  val name: String

  val maybeLastUpdateThreshold: Option[FiniteDuration]

  val description: Boolean => String

  override val toString: String = getClass.getSimpleName.split("\\$").last
}

case object ApiFeedSource extends FeedSource {
  val name: String = "API"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = None

  val description: Boolean => String = isLiveFeedAvailable => if (isLiveFeedAvailable)
    "Actual passenger nationality and age data when available."
  else
    "Actual passenger numbers and nationality data when available."
}

case object AclFeedSource extends FeedSource {
  val name: String = "ACL"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(36 hours)

  val description: Boolean => String = _ => "Flight schedule for up to 6 months."
}

case object ForecastFeedSource extends FeedSource {
  val name: String = "Port forecast"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = None

  val description: Boolean => String = _ => "Updated forecast of passenger numbers."
}

case object LiveFeedSource extends FeedSource {
  val name: String = "Port live"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(12 hours)

  val description: Boolean => String = _ => "Up-to-date passenger numbers, estimated and actual arrival times, gates and stands."
}

case object ScenarioSimulationSource extends FeedSource {
  val name: String = "Scenario Simulation"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(12 hours)

  val description: Boolean => String = _ => "An altered arrival to explore a simulated scenario."
}

case object LiveBaseFeedSource extends FeedSource {
  val name: String = "Cirium live"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = Option(12 hours)

  val description: Boolean => String = isLiveFeedAvailable => if (isLiveFeedAvailable)
    "Estimated and actual arrival time updates where not available from live feed."
  else
    "Estimated and actual arrival time updates."
}

case object UnknownFeedSource extends FeedSource {
  val name: String = "Unknown"

  val maybeLastUpdateThreshold: Option[FiniteDuration] = None

  val description: Boolean => String = _ => ""
}

object FeedSource {
  def feedSources: Set[FeedSource] = Set(ApiFeedSource, AclFeedSource, ForecastFeedSource, LiveFeedSource, LiveBaseFeedSource)

  def apply(feedSource: String): Option[FeedSource] = feedSources.find(fs => fs.toString == feedSource)

  implicit val feedSourceReadWriter: ReadWriter[FeedSource] =
    readwriter[Value].bimap[FeedSource](
      feedSource => feedSource.toString,
      (s: Value) => apply(s.str).getOrElse(UnknownFeedSource)
    )
}

case class ArrivalKey(origin: PortCode,
                      voyageNumber: VoyageNumber,
                      scheduled: Long) extends Ordered[ArrivalKey] with WithTimeAccessor {
  override def compare(that: ArrivalKey): Int =
    scheduled.compareTo(that.scheduled) match {
      case 0 => origin.compare(that.origin) match {
        case 0 => voyageNumber.compare(that.voyageNumber)
        case c => c
      }
      case c => c
    }

  override def timeValue: MillisSinceEpoch = scheduled
}

object ArrivalKey {

  implicit val rw: ReadWriter[ArrivalKey] = macroRW

  def apply(arrival: Arrival): ArrivalKey = ArrivalKey(arrival.Origin, arrival.VoyageNumber, arrival.Scheduled)

  def atTime: MillisSinceEpoch => ArrivalKey = (time: MillisSinceEpoch) => ArrivalKey(PortCode(""), VoyageNumber(0), time)
}

case class ArrivalUpdate(old: Arrival, updated: Arrival)

object ArrivalsDiff {
  val empty: ArrivalsDiff = ArrivalsDiff(Seq(), Seq())

  def apply(toUpdate: Iterable[Arrival], toRemove: Iterable[Arrival]): ArrivalsDiff = ArrivalsDiff(
    ISortedMap[UniqueArrival, Arrival]() ++ toUpdate.map(a => (a.unique, a)), toRemove
  )
}

case class ArrivalsDiff(toUpdate: ISortedMap[UniqueArrival, Arrival], toRemove: Iterable[Arrival]) extends FlightUpdates {
  private val minutesFromUpdate: Iterable[MillisSinceEpoch] = toUpdate.values.flatMap(_.pcpRange)
  private val minutesFromRemoval: Iterable[MillisSinceEpoch] = toRemove.flatMap(_.pcpRange)
  val updateMinutes: Iterable[MillisSinceEpoch] = minutesFromUpdate ++ minutesFromRemoval

  def diffWith(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): FlightsWithSplitsDiff = {
    val updatedFlights = toUpdate
      .map {
        case (key, incomingArrival) =>
          flights.flights.get(key) match {
            case Some(existingFws) if existingFws.apiFlight == incomingArrival =>
              None
            case Some(existingFws) =>
              val incomingWithRedListPaxRetained = incomingArrival.copy(RedListPax = existingFws.apiFlight.RedListPax)
              Some(existingFws.copy(apiFlight = incomingWithRedListPaxRetained, lastUpdated = Option(nowMillis)))
            case None =>
              Some(ApiFlightWithSplits(incomingArrival, Set(), Option(nowMillis)))
          }
      }
      .collect { case Some(updatedFlight) => updatedFlight }

    FlightsWithSplitsDiff(updatedFlights, toRemove.map(_.unique))
  }
}

object MonthStrings {
  val months = List(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  )
}

trait SDateLike {
  import MonthStrings._

  def ddMMyyString: String = f"$getDate%02d/$getMonth%02d/${getFullYear - 2000}%02d"

  def <(other: SDateLike): Boolean = millisSinceEpoch < other.millisSinceEpoch

  def >(other: SDateLike): Boolean = millisSinceEpoch > other.millisSinceEpoch

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

  def millisSinceEpochToMinuteBoundary: MillisSinceEpoch = millisSinceEpoch - (millisSinceEpoch % 60000)

  def toISOString(): String

  def addDays(daysToAdd: Int): SDateLike

  def addMonths(monthsToAdd: Int): SDateLike

  def addHours(hoursToAdd: Int): SDateLike

  def addMinutes(minutesToAdd: Int): SDateLike

  def addMillis(millisToAdd: Int): SDateLike

  def addMillis(millisToAdd: MillisSinceEpoch): SDateLike = addMillis(millisToAdd.toInt)

  def roundToMinute(): SDateLike = {
    val remainder = millisSinceEpoch % 60000
    addMillis(-1 * remainder.toInt)
  }

  def toLocalDateTimeString(): String

  def toLocalDate: LocalDate

  def toUtcDate: UtcDate

  def toISODateOnly: String = f"${getFullYear()}-${getMonth()}%02d-${getDate()}%02d"

  def toHoursAndMinutes: String = f"${getHours()}%02d:${getMinutes()}%02d"

  def prettyDateTime(): String = f"${getDate()}%02d-${getMonth()}%02d-${getFullYear()} ${getHours()}%02d:${getMinutes()}%02d"

  def prettyTime(): String = f"${getHours()}%02d:${getMinutes()}%02d"

  def hms(): String = f"${getHours()}%02d:${getMinutes()}%02d:${getSeconds()}%02d"

  def getZone(): String

  def getTimeZoneOffsetMillis(): MillisSinceEpoch

  def startOfTheMonth(): SDateLike

  def getUtcLastMidnight: SDateLike

  def getLocalLastMidnight: SDateLike

  def getLocalNextMidnight: SDateLike

  def toIsoMidnight = s"${getFullYear()}-${getMonth()}-${getDate()}T00:00"

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

  def <=(compareTo: MillisSinceEpoch): Boolean = millisSinceEpoch <= compareTo

  def >=(compareTo: MillisSinceEpoch): Boolean = millisSinceEpoch >= compareTo

  def daysBetweenInclusive(that: SDateLike): Int = ((millisSinceEpoch - that.millisSinceEpoch) / oneDayMillis).abs.toInt + 1

  def isHistoricDate(now: SDateLike): Boolean = millisSinceEpoch < now.getLocalLastMidnight.millisSinceEpoch
}

case class RemoveFlight(flightKey: UniqueArrival)

trait MinuteComparison[A <: WithLastUpdated] {
  def maybeUpdated(existing: A, now: MillisSinceEpoch): Option[A]
}

trait PortStateMinutes[MinuteType, IndexType <: WithTimeAccessor] {
  val asContainer: MinutesContainer[MinuteType, IndexType]

  def isEmpty: Boolean

  def nonEmpty: Boolean = !isEmpty

  def addIfUpdated[A <: MinuteComparison[C], B <: WithTerminal[B], C <: WithLastUpdated](maybeExisting: Option[C],
                                                                                         now: MillisSinceEpoch,
                                                                                         existingUpdates: List[C],
                                                                                         incoming: A,
                                                                                         newMinute: () => C): List[C] = {
    maybeExisting match {
      case None => newMinute() :: existingUpdates
      case Some(existing) => incoming.maybeUpdated(existing, now) match {
        case None => existingUpdates
        case Some(updated) => updated :: existingUpdates
      }
    }
  }
}

trait PortStateQueueMinutes extends PortStateMinutes[CrunchMinute, TQM]

trait PortStateStaffMinutes extends PortStateMinutes[StaffMinute, TM]


case class CrunchResult(firstTimeMillis: MillisSinceEpoch,
                        intervalMillis: MillisSinceEpoch,
                        recommendedDesks: IndexedSeq[Int],
                        waitTimes: Seq[Int])

case class AirportInfo(airportName: String, city: String, country: String, code: String)

object AirportInfo {
  implicit val rw: ReadWriter[AirportInfo] = macroRW
}

case class BuildVersion(version: String, requiresReload: Boolean = false)

object BuildVersion {
  implicit val rw: ReadWriter[BuildVersion] = macroRW
}

case class ApplicationConfig(rootDomain: String, useHttps: Boolean) {
  val urls: Urls = Urls(rootDomain, useHttps)

  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = AirportConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet
}

object ApplicationConfig {
  implicit val rw: ReadWriter[ApplicationConfig] = macroRW
}

object DataUpdates {

  trait Updates

  trait FlightUpdates extends Updates

  trait MinuteUpdates extends Updates

}

class ArrivalsRestorer[A <: WithUnique[UniqueArrival]] {
  var arrivals: Map[UniqueArrival, A] = Map()

  def removeHashLegacies(removals: Iterable[Int]): Unit = removals.foreach(keyToRemove => arrivals = arrivals.filterKeys(_.legacyUniqueId != keyToRemove))

  def applyUpdates(updates: Iterable[A]): Unit = updates.foreach { update =>
    arrivals = arrivals + ((update.unique, update))
  }

  def remove(removals: Iterable[UniqueArrivalLike]): Unit =
    arrivals = ArrivalsRemoval.removeArrivals(removals, arrivals)

  def finish(): Unit = arrivals = Map()
}

object ArrivalsRemoval {
  def removeArrivals[A](removals: Iterable[UniqueArrivalLike], arrivals: Map[UniqueArrival, A]): Map[UniqueArrival, A] = {
    val keys = removals.collect { case k: UniqueArrival => k }
    val minusRemovals = arrivals -- keys
    val legacyKeys = removals.collect { case lk: LegacyUniqueArrival => lk }
    if (legacyKeys.nonEmpty) {
      legacyKeys.foldLeft(minusRemovals) {
        case (acc, legacyKey) => acc.filterKeys(_.legacyUniqueArrival != legacyKey)
      }
    } else minusRemovals
  }
}

object FlightsApi {

  case class Flights(flights: Iterable[Arrival])

  case class FlightsWithSplits(flights: Map[UniqueArrival, ApiFlightWithSplits]) {
    val isEmpty: Boolean = flights.isEmpty
    val nonEmpty: Boolean = !isEmpty

    def scheduledSince(sinceMillis: MillisSinceEpoch): FlightsWithSplits = FlightsWithSplits(flights.filter {
      case (UniqueArrival(_, _, scheduledMillis, _), _) => scheduledMillis >= sinceMillis
    })

    def scheduledOrPcpWindow(start: SDateLike, end: SDateLike): FlightsWithSplits = {
      val inWindow = flights.filter {
        case (_, fws) =>
          val pcpMatches = fws.apiFlight.hasPcpDuring(start, end)
          val scheduledMatches = start <= fws.apiFlight.Scheduled && end >= fws.apiFlight.Scheduled
          scheduledMatches || pcpMatches
      }
      FlightsWithSplits(inWindow)
    }

    def forTerminal(terminal: Terminal): FlightsWithSplits = {
      val inTerminal = flights.filter {
        case (_, fws) => fws.apiFlight.Terminal == terminal
      }
      FlightsWithSplits(inTerminal)
    }

    def updatedSince(sinceMillis: MillisSinceEpoch): FlightsWithSplits =
      FlightsWithSplits(flights.filter {
        case (_, fws) => fws.lastUpdated.getOrElse(0L) > sinceMillis
      })

    def --(toRemove: Iterable[UniqueArrival]): FlightsWithSplits = FlightsWithSplits(flights -- toRemove)

    def ++(toUpdate: Iterable[(UniqueArrival, ApiFlightWithSplits)]): FlightsWithSplits = FlightsWithSplits(flights ++ toUpdate)

    def +(toAdd: ApiFlightWithSplits): FlightsWithSplits = FlightsWithSplits(flights.updated(toAdd.unique, toAdd))

    def ++(other: FlightsWithSplits): FlightsWithSplits = FlightsWithSplits(flights ++ other.flights)
  }

  object FlightsWithSplits {
    val empty: FlightsWithSplits = FlightsWithSplits(Map[UniqueArrival, ApiFlightWithSplits]())

    def apply(flights: Iterable[ApiFlightWithSplits]): FlightsWithSplits = FlightsWithSplits(flights.map(fws => (fws.unique, fws)).toMap)
  }

  case object NoFlightUpdates extends FlightUpdates

  object SplitsForArrivals {
    val empty: SplitsForArrivals = SplitsForArrivals(Map())
  }

  case class SplitsForArrivals(splits: Map[UniqueArrival, Set[Splits]]) extends FlightUpdates {
    val updatedMillis: Iterable[MillisSinceEpoch] = splits.keys.map(_.scheduled)

    def diff(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): FlightsWithSplitsDiff = {
      val updatedFlights = splits
        .map {
          case (key, newSplits) =>
            flights.flights.get(key)
              .map(fws => (fws, newSplits.diff(fws.splits)))
              .collect {
                case (fws, updatedSplits) if updatedSplits.nonEmpty =>
                  val updatedSources = updatedSplits.map(_.source)
                  val mergedSplits = fws.splits.filterNot(s => updatedSources.contains(s.source)) ++ updatedSplits
                  val updatedArrival = mergedSplits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages) match {
                    case None =>
                      fws.apiFlight
                    case Some(liveSplit) =>
                      fws.apiFlight.copy(
                        ApiPax = Option(Math.round(liveSplit.totalExcludingTransferPax).toInt),
                        FeedSources = fws.apiFlight.FeedSources + ApiFeedSource)
                  }

                  fws.copy(apiFlight = updatedArrival, splits = mergedSplits, lastUpdated = Option(nowMillis))
              }
        }
        .collect { case Some(flight) => flight }

      FlightsWithSplitsDiff(updatedFlights, List())
    }

    def ++(tuple: (UniqueArrival, Set[Splits])): IMap[UniqueArrival, Set[Splits]] = splits + tuple
  }

  case class FlightsWithSplitsDiff(flightsToUpdate: Iterable[ApiFlightWithSplits], arrivalsToRemove: Iterable[UniqueArrivalLike]) extends FlightUpdates {
    def isEmpty: Boolean = flightsToUpdate.isEmpty && arrivalsToRemove.isEmpty

    def nonEmpty: Boolean = !isEmpty

    val updateMinutes: Iterable[MillisSinceEpoch] = flightsToUpdate.flatMap(_.apiFlight.pcpRange)

    def applyTo(flightsWithSplits: FlightsWithSplits,
                nowMillis: MillisSinceEpoch): (FlightsWithSplits, Iterable[MillisSinceEpoch]) = {
      val updated = flightsWithSplits.flights ++ flightsToUpdate.map(f => (f.apiFlight.unique, f.copy(lastUpdated = Option(nowMillis))))

      val minusRemovals: Map[UniqueArrival, ApiFlightWithSplits] = ArrivalsRemoval.removeArrivals(arrivalsToRemove, updated)

      val asMap: IMap[UniqueArrival, ApiFlightWithSplits] = flightsWithSplits.flights

      val minutesFromRemovalsInExistingState: Iterable[MillisSinceEpoch] = arrivalsToRemove
        .flatMap {
          case r: UniqueArrival =>
            asMap.get(r).map(_.apiFlight.pcpRange).getOrElse(List())
          case r: LegacyUniqueArrival =>
            asMap.collect { case (ua, a) if ua.equalsLegacy(r) => a }.flatMap(_.apiFlight.pcpRange)
        }

      val minutesFromExistingStateUpdatedFlights = flightsToUpdate
        .flatMap { fws =>
          asMap.get(fws.unique) match {
            case None => List()
            case Some(f) => f.apiFlight.pcpRange
          }
        }

      val updatedMinutesFromFlights = minutesFromRemovalsInExistingState ++
        updateMinutes ++
        minutesFromExistingStateUpdatedFlights

      (FlightsWithSplits(minusRemovals.toMap), updatedMinutesFromFlights)
    }

    lazy val terminals: Set[Terminal] = flightsToUpdate.map(_.apiFlight.Terminal).toSet ++
      arrivalsToRemove.map(_.terminal).toSet

    def ++(other: FlightsWithSplitsDiff): FlightsWithSplitsDiff =
      FlightsWithSplitsDiff(flightsToUpdate ++ other.flightsToUpdate, arrivalsToRemove ++ other.arrivalsToRemove)

    def window(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): FlightsWithSplitsDiff =
      FlightsWithSplitsDiff(flightsToUpdate.filter(fws =>
        startMillis <= fws.apiFlight.Scheduled && fws.apiFlight.Scheduled <= endMillis
      ), arrivalsToRemove.filter(ua =>
        startMillis <= ua.scheduled && ua.scheduled <= endMillis
      ))

    def forTerminal(terminal: Terminal): FlightsWithSplitsDiff = FlightsWithSplitsDiff(
      flightsToUpdate.filter(_.apiFlight.Terminal == terminal),
      arrivalsToRemove.filter(_.terminal == terminal)
    )
  }

  object FlightsWithSplitsDiff {
    val empty: FlightsWithSplitsDiff = FlightsWithSplitsDiff(List(), List())
  }

}

object PassengerSplits {
  type PaxTypeAndQueueCounts = Seq[ApiPaxTypeAndQueueCount]

  case object FlightsNotFound

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: MilliDate)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: MilliDate,
                             paxSplits: List[ApiPaxTypeAndQueueCount])

}

object MilliTimes {
  val oneSecondMillis: Int = 1000
  val oneMinuteMillis: Int = 60 * oneSecondMillis
  val oneHourMillis: Int = 60 * oneMinuteMillis
  val oneDayMillis: Int = 24 * oneHourMillis
  val minutesInADay: Int = 60 * 24

  def timeToNearestMinute(t: MillisSinceEpoch): MillisSinceEpoch = round(t / 60000d) * 60000

  val fifteenMinutesMillis: Int = oneMinuteMillis * 15
  val fifteenMinuteSlotsInDay: Int = 4 * 24
}

object CrunchApi {
  type MillisSinceEpoch = Long

  case class PortStateError(message: String)

  object PortStateError {
    implicit val rw: ReadWriter[PortStateError] = macroRW
  }

  trait MinuteLike[A, B] {
    val minute: MillisSinceEpoch
    val lastUpdated: Option[MillisSinceEpoch]
    val terminal: Terminal

    def maybeUpdated(existing: A, now: MillisSinceEpoch): Option[A]

    val key: B

    def toUpdatedMinute(now: MillisSinceEpoch): A

    def toMinute: A
  }

  trait TerminalQueueMinute {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
  }

  trait TerminalMinute {
    val terminal: Terminal
    val minute: MillisSinceEpoch
  }

  case class StaffMinute(terminal: Terminal,
                         minute: MillisSinceEpoch,
                         shifts: Int,
                         fixedPoints: Int,
                         movements: Int,
                         lastUpdated: Option[MillisSinceEpoch] = None) extends MinuteLike[StaffMinute, TM] with TerminalMinute with WithLastUpdated with MinuteComparison[StaffMinute] {
    def equals(candidate: StaffMinute): Boolean =
      this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: TM = TM(terminal, minute)
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

    override def maybeUpdated(existing: StaffMinute, now: MillisSinceEpoch): Option[StaffMinute] =
      if (existing.shifts != shifts || existing.fixedPoints != fixedPoints || existing.movements != movements) Option(existing.copy(
        shifts = shifts, fixedPoints = fixedPoints, movements = movements, lastUpdated = Option(now)
      ))
      else None

    override def toUpdatedMinute(now: MillisSinceEpoch): StaffMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: StaffMinute = this
  }

  object StaffMinute {
    def empty: StaffMinute = StaffMinute(Terminal(""), 0L, 0, 0, 0, None)

    implicit val rw: ReadWriter[StaffMinute] = macroRW
  }

  case class StaffMinutes(minutes: Seq[StaffMinute]) extends PortStateStaffMinutes with MinutesLike[StaffMinute, TM] {
    override val asContainer: MinutesContainer[StaffMinute, TM] = MinutesContainer(minutes)

    override def isEmpty: Boolean = minutes.isEmpty

    lazy val millis: Iterable[MillisSinceEpoch] = minutes.map(_.minute)
  }

  object StaffMinutes {
    def apply(minutesByKey: IMap[TM, StaffMinute]): StaffMinutes = StaffMinutes(minutesByKey.values.toSeq)

    implicit val rw: ReadWriter[StaffMinutes] = macroRW
  }

  case class CrunchMinute(terminal: Terminal,
                          queue: Queue,
                          minute: MillisSinceEpoch,
                          paxLoad: Double,
                          workLoad: Double,
                          deskRec: Int,
                          waitTime: Int,
                          deployedDesks: Option[Int] = None,
                          deployedWait: Option[Int] = None,
                          actDesks: Option[Int] = None,
                          actWait: Option[Int] = None,
                          lastUpdated: Option[MillisSinceEpoch] = None) extends MinuteLike[CrunchMinute, TQM] with WithLastUpdated {
    def equals(candidate: CrunchMinute): Boolean = this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (!equals(existing)) Option(copy(lastUpdated = Option(now)))
      else None

    lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = this.copy(lastUpdated = Option(now))

    override val toMinute: CrunchMinute = this

    def prettyPrint(implicit niceDate: MillisSinceEpoch => String): String = {
      s"CrunchMinute($terminal, $queue, ${niceDate(minute)}, $paxLoad pax, $workLoad work, $deskRec desks, $waitTime waits, $deployedDesks dep desks, $deployedWait dep wait, $actDesks act desks, $actWait act wait, ${lastUpdated.map(niceDate)} updated)"
    }
  }

  object CrunchMinute {
    def apply(tqm: TQM, ad: DeskStat, now: MillisSinceEpoch): CrunchMinute = CrunchMinute(
      terminal = tqm.terminal,
      queue = tqm.queue,
      minute = tqm.minute,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      actDesks = ad.desks,
      actWait = ad.waitTime,
      lastUpdated = Option(now)
    )

    implicit val rw: ReadWriter[CrunchMinute] = macroRW
  }

  trait DeskRecMinuteLike {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
    val paxLoad: Double
    val workLoad: Double
    val deskRec: Int
    val waitTime: Int
  }

  case class DeskRecMinute(terminal: Terminal,
                           queue: Queue,
                           minute: MillisSinceEpoch,
                           paxLoad: Double,
                           workLoad: Double,
                           deskRec: Int,
                           waitTime: Int) extends DeskRecMinuteLike with MinuteComparison[CrunchMinute] with MinuteLike[CrunchMinute, TQM] {
    lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.paxLoad != paxLoad || existing.workLoad != workLoad || existing.deskRec != deskRec || existing.waitTime != waitTime)
        Option(existing.copy(
          paxLoad = paxLoad, workLoad = workLoad, deskRec = deskRec, waitTime = waitTime, lastUpdated = Option(now)
        ))
      else None

    override val lastUpdated: Option[MillisSinceEpoch] = None

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: CrunchMinute = CrunchMinute(
      terminal, queue, minute, paxLoad, workLoad, deskRec, waitTime, lastUpdated = None)
  }

  case class DeskRecMinutes(minutes: Seq[DeskRecMinute]) extends PortStateQueueMinutes {
    override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(minutes)

    override def isEmpty: Boolean = minutes.isEmpty
  }

  trait SimulationMinuteLike {
    val terminal: Terminal
    val queue: Queue
    val minute: MillisSinceEpoch
    val desks: Int
    val waitTime: Int
  }

  case class DeskStat(desks: Option[Int], waitTime: Option[Int]) extends MinuteComparison[CrunchMinute] {
    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.actDesks != desks || existing.actWait != waitTime) Option(existing.copy(
        actDesks = desks, actWait = waitTime, lastUpdated = Option(now)
      ))
      else None
  }

  case class DeskStatMinute(terminal: Terminal,
                            queue: Queue,
                            minute: MillisSinceEpoch,
                            deskStat: DeskStat) extends MinuteLike[CrunchMinute, TQM] {
    override val key: TQM = TQM(terminal, queue, minute)

    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.actDesks != deskStat.desks || existing.actWait != deskStat.waitTime) Option(existing.copy(
        actDesks = deskStat.desks, actWait = deskStat.waitTime, lastUpdated = Option(now)
      ))
      else None

    override val lastUpdated: Option[MillisSinceEpoch] = None

    override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

    override def toMinute: CrunchMinute = CrunchMinute(
      terminal, queue, minute, 0d, 0d, 0, 0, None, None, deskStat.desks, deskStat.waitTime, None)
  }

  case class ActualDeskStats(portDeskSlots: IMap[Terminal, IMap[Queue, IMap[MillisSinceEpoch, DeskStat]]]) extends PortStateQueueMinutes {
    override val asContainer: MinutesContainer[CrunchMinute, TQM] = MinutesContainer(deskStatMinutes)

    override def isEmpty: Boolean = portDeskSlots.isEmpty

    lazy val deskStatMinutes: Iterable[DeskStatMinute] = for {
      (tn, queueMinutes) <- portDeskSlots
      (qn, deskStats) <- queueMinutes
      (startMinute, deskStat) <- deskStats
      minute <- startMinute until startMinute + 15 * oneMinuteMillis by oneMinuteMillis
    } yield DeskStatMinute(tn, qn, minute, deskStat)
  }

  sealed trait MinutesLike[A, B] {
    def minutes: Iterable[MinuteLike[A, B]]
  }

  object MinutesContainer {
    def empty[A, B <: WithTimeAccessor]: MinutesContainer[A, B] = MinutesContainer[A, B](Iterable())
  }

  case class MinutesContainer[A, B <: WithTimeAccessor](minutes: Iterable[MinuteLike[A, B]]) extends MinuteUpdates {
    def window(start: SDateLike, end: SDateLike): MinutesContainer[A, B] = {
      val startMillis = start.millisSinceEpoch
      val endMillis = end.millisSinceEpoch
      MinutesContainer(minutes.filter(i => startMillis <= i.minute && i.minute <= endMillis))
    }

    def ++(that: MinutesContainer[A, B]): MinutesContainer[A, B] = MinutesContainer(minutes ++ that.minutes)

    def updatedSince(sinceMillis: MillisSinceEpoch): MinutesContainer[A, B] = MinutesContainer(minutes.filter(_.lastUpdated.getOrElse(0L) > sinceMillis))

    def contains(clazz: Class[_]): Boolean = minutes.headOption match {
      case Some(x) if x.getClass == clazz => true
      case _ => false
    }

    lazy val indexed: IMap[B, A] = minutes.map(m => (m.key, m.toMinute)).toMap
  }

  case class CrunchMinutes(minutes: Set[CrunchMinute]) extends MinutesLike[CrunchMinute, TQM]

  case class PortStateUpdates(latest: MillisSinceEpoch,
                              flights: Set[ApiFlightWithSplits],
                              queueMinutes: Set[CrunchMinute],
                              staffMinutes: Set[StaffMinute])

  object PortStateUpdates {
    implicit val rw: ReadWriter[PortStateUpdates] = macroRW
  }

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  case class ForecastPeriod(days: IMap[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  case class ForecastHeadlineFigures(queueDayHeadlines: Seq[QueueHeadline])

  case class QueueHeadline(day: MillisSinceEpoch, queue: Queue, paxNos: Int, workload: Int)

  def groupCrunchMinutesByX(groupSize: Int)
                           (crunchMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])],
                            terminalName: Terminal,
                            queueOrder: List[Queue]): Seq[(MillisSinceEpoch, Seq[CrunchMinute])] = {
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queue)
      val startMinute = group.map(_._1).min
      val queueCrunchMinutes = queueOrder.collect {
        case qn if byQueueName.contains(qn) =>
          val queueMinutes: Seq[CrunchMinute] = byQueueName(qn)
          val allActDesks = queueMinutes.collect {
            case CrunchMinute(_, _, _, _, _, _, _, _, _, Some(ad), _, _) => ad
          }
          val actDesks = if (allActDesks.isEmpty) None else Option(allActDesks.max)
          val allActWaits = queueMinutes.collect {
            case CrunchMinute(_, _, _, _, _, _, _, _, _, _, Some(aw), _) => aw
          }
          val actWaits = if (allActWaits.isEmpty) None else Option(allActWaits.max)
          CrunchMinute(
            terminal = terminalName,
            queue = qn,
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

  def terminalMinutesByMinute[T <: MinuteLike[A, B], A, B](minutes: List[T],
                                                           terminalName: Terminal): Seq[(MillisSinceEpoch, List[T])] = minutes
    .filter(_.terminal == terminalName)
    .groupBy(_.minute)
    .toList
    .sortBy(_._1)

}

trait Api {

  def getShifts(maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def getShiftsForMonth(month: MillisSinceEpoch, terminalName: Terminal): Future[ShiftAssignments]

  def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: Terminal): Future[Option[ForecastPeriodWithHeadlines]]

  def getLoggedInUser(): LoggedInUser

  def getKeyCloakUsers(): Future[List[KeyCloakUser]]

  def getKeyCloakGroups(): Future[List[KeyCloakGroup]]

  def getKeyCloakUserGroups(userId: UUID): Future[Set[KeyCloakGroup]]

  def addUserToGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def removeUserFromGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def getShowAlertModalDialog(): Boolean
}


