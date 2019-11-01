package drt.shared

import java.util.UUID

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, _}
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.SplitRatiosNs.SplitSources
import ujson.Js.Value
import upickle.Js
import upickle.default.{macroRW, readwriter, ReadWriter => RW}

import scala.collection.immutable.{NumericRange, Map => IMap, SortedMap => ISortedMap}
import scala.collection.{Map, SortedMap}
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

  def atTime: MillisSinceEpoch => MilliDate = (time: MillisSinceEpoch) => MilliDate(time)
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

case class ApiPaxTypeAndQueueCount(passengerType: PaxType, queueType: String, paxCount: Double, nationalities: Option[IMap[String, Double]])

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

trait WithLastUpdated {
  def lastUpdated: Option[MillisSinceEpoch]
}

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[Splits], lastUpdated: Option[MillisSinceEpoch] = None)
  extends WithUnique[UniqueArrival]
    with WithLastUpdated {

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

  override val unique: UniqueArrival = apiFlight.unique
}

object ApiFlightWithSplits {
  implicit val rw: RW[ApiFlightWithSplits] = macroRW
}

trait WithTimeAccessor {
  def timeValue: MillisSinceEpoch
}

case class TQM(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch)
  extends Ordered[TQM]
    with WithTimeAccessor
    with WithTerminal[TQM] {
  override def equals(o: scala.Any): Boolean = o match {
    case TQM(t, q, m) => t == terminalName && q == queueName && m == minute
    case _ => false
  }

  lazy val comparisonString = s"$minute-$queueName-$terminalName"

  override def compare(that: TQM): Int = this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = minute

  override def terminal: TerminalName = terminalName
}

object TQM {
  implicit val rw: RW[TQM] = macroRW

  def apply(crunchMinute: CrunchMinute): TQM = TQM(crunchMinute.terminalName, crunchMinute.queueName, crunchMinute.minute)

  def atTime: MillisSinceEpoch => TQM = (time: MillisSinceEpoch) => TQM("", "", time)
}

case class TM(terminalName: TerminalName, minute: MillisSinceEpoch)
  extends Ordered[TM]
    with WithTimeAccessor
    with WithTerminal[TM] {
  override def equals(o: scala.Any): Boolean = o match {
    case TM(t, m) => t == terminalName && m == minute
    case _ => false
  }

  lazy val comparisonString = s"$minute-$terminalName"

  override def compare(that: TM): Int = this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = minute

  override def terminal: TerminalName = terminalName
}

object TM {
  implicit val rw: RW[TM] = macroRW

  def apply(staffMinute: StaffMinute): TM = TM(staffMinute.terminalName, staffMinute.minute)

  def atTime: MillisSinceEpoch => TM = (time: MillisSinceEpoch) => TM("", time)
}

trait WithLegacyUniqueId[LI, I] extends Ordered[I] {
  def uniqueId: LI
}

trait WithUnique[I] {
  def unique: I
}

trait WithTerminal[A] extends Ordered[A] {
  def terminal: TerminalName
}

case class UniqueArrival(number: Int, terminalName: TerminalName, scheduled: MillisSinceEpoch)
  extends WithLegacyUniqueId[Int, UniqueArrival]
    with WithTimeAccessor
    with WithTerminal[UniqueArrival] {
  lazy val comparisonStringForEquality = s"$scheduled-$terminalName-$number"

  override def equals(that: scala.Any): Boolean = that match {
    case o: UniqueArrival => o.hashCode == hashCode
    case _ => false
  }

  override def compare(that: UniqueArrival): Int =
    this.comparisonStringForEquality.compareTo(that.comparisonStringForEquality)

  lazy val uniqueId: Int = uniqueStr.hashCode
  lazy val uniqueStr: String = s"$terminalName$scheduled$number"

  override val hashCode: Int = uniqueId

  override def timeValue: MillisSinceEpoch = scheduled

  override val terminal: TerminalName = terminalName
}

object UniqueArrival {
  implicit val rw: RW[UniqueArrival] = macroRW

  def apply(arrival: Arrival): UniqueArrival = UniqueArrival(arrival.flightNumber, arrival.Terminal, arrival.Scheduled)

  def atTime: MillisSinceEpoch => UniqueArrival = (time: MillisSinceEpoch) => UniqueArrival(0, "", time)
}

case class CodeShareKeyOrderedBySchedule(scheduled: Long, terminalName: TerminalName, origin: String) extends Ordered[CodeShareKeyOrderedBySchedule] with WithTimeAccessor {
  lazy val comparisonString = s"$scheduled-$terminalName-$origin"

  override def equals(o: scala.Any): Boolean = o match {
    case o: CodeShareKeyOrderedBySchedule => o.comparisonString == comparisonString
    case _ => false
  }

  override def compare(that: CodeShareKeyOrderedBySchedule): Int =
    if (this.equals(that)) 0 else this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = scheduled
}

object CodeShareKeyOrderedBySchedule {
  def apply(arrival: Arrival): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(arrival.Scheduled, arrival.Terminal, arrival.Origin)

  def apply(fws: ApiFlightWithSplits): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(fws.apiFlight.Scheduled, fws.apiFlight.Terminal, fws.apiFlight.Origin)

  def atTime: MillisSinceEpoch => CodeShareKeyOrderedBySchedule = (millis: MillisSinceEpoch) => CodeShareKeyOrderedBySchedule(millis, "", "")
}

case class CodeShareKeyOrderedByDupes[A](scheduled: Long, terminalName: TerminalName, origin: String, arrivalKeys: Set[A]) extends Ordered[CodeShareKeyOrderedByDupes[A]] {
  lazy val comparisonStringForEquality = s"$scheduled-$terminalName-$origin"

  lazy val comparisonStringForOrdering = s"${100 - arrivalKeys.size}-$scheduled-$terminalName-$origin"

  override def equals(o: scala.Any): Boolean = o match {
    case o: CodeShareKeyOrderedByDupes[A] => o.comparisonStringForEquality == comparisonStringForEquality
    case _ => false
  }

  override def compare(that: CodeShareKeyOrderedByDupes[A]): Int =
    if (this.equals(that)) 0 else this.comparisonStringForOrdering.compareTo(that.comparisonStringForOrdering)
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
                    AirportID: String,
                    Terminal: String,
                    rawICAO: String,
                    rawIATA: String,
                    Origin: String,
                    Scheduled: MillisSinceEpoch,
                    PcpTime: Option[MillisSinceEpoch],
                    FeedSources: Set[FeedSource],
                    CarrierScheduled: Option[MillisSinceEpoch] = None
                  ) extends WithUnique[UniqueArrival] {
  lazy val ICAO: String = Arrival.standardiseFlightCode(rawICAO)
  lazy val IATA: String = Arrival.standardiseFlightCode(rawIATA)
  val paxOffPerMinute = 20

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

  def basicForComparison: Arrival = copy(PcpTime = None)

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

  lazy val pax: Int = ActPax.getOrElse(0)

  lazy val minutesOfPaxArrivals: Int =
    if (pax == 0) 0
    else (pax.toDouble / paxOffPerMinute).ceil.toInt - 1

  def pcpRange(): NumericRange[MillisSinceEpoch] = {
    val pcpStart = PcpTime.getOrElse(0L)
    val pcpEnd = pcpStart + oneMinuteMillis * minutesOfPaxArrivals
    pcpStart to pcpEnd by oneMinuteMillis
  }

  lazy val unique: UniqueArrival = UniqueArrival(flightNumber, Terminal, Scheduled)
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

case object LiveBaseFeedSource extends FeedSource

case object UnknownFeedSource extends FeedSource

object FeedSource {
  def feedSources: Set[FeedSource] = Set(ApiFeedSource, AclFeedSource, ForecastFeedSource, LiveFeedSource, LiveBaseFeedSource)

  def apply(name: String): Option[FeedSource] = feedSources.find(fs => fs.toString == name)

  implicit val feedSourceReadWriter: RW[FeedSource] =
    readwriter[Js.Value].bimap[FeedSource](
      feedSource => feedSource.toString,
      (s: Value) => apply(s.str).getOrElse(UnknownFeedSource)
    )
}

case class ArrivalKey(origin: String, voyageNumber: String, scheduled: Long) extends Ordered[ArrivalKey] with WithTimeAccessor {
  lazy val comparisonString = s"$scheduled-$origin-$voyageNumber"

  override def compare(that: ArrivalKey): Int = this.comparisonString.compareTo(that.comparisonString)

  override def timeValue: MillisSinceEpoch = scheduled
}

object ArrivalKey {
  def apply(arrival: Arrival): ArrivalKey = ArrivalKey(arrival.Origin, arrival.voyageNumberPadded, arrival.Scheduled)

  def atTime: MillisSinceEpoch => ArrivalKey = (time: MillisSinceEpoch) => ArrivalKey("", "", time)
}

case class ArrivalUpdate(old: Arrival, updated: Arrival)

case class ArrivalsDiff(toUpdate: ISortedMap[UniqueArrival, Arrival], toRemove: Set[Arrival])

trait SDateLike {

  def ddMMyyString: String = f"$getDate%02d/$getMonth%02d/${getFullYear - 2000}%02d"

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

case class RemoveFlight(flightKey: UniqueArrival)

trait MinuteComparison[A <: WithLastUpdated] {
  def maybeUpdated(existing: A, now: MillisSinceEpoch): Option[A]
}

trait PortStateMinutes {
  def applyTo(portStateMutable: PortStateMutable, now: MillisSinceEpoch): PortStateDiff

  def addIfUpdated[A <: MinuteComparison[C], B <: WithTerminal[B], C <: WithLastUpdated](maybeExisting: Option[C], now: MillisSinceEpoch, existingUpdates: List[C], incoming: A, newMinute: () => C): List[C] = {
    maybeExisting match {
      case None => newMinute() :: existingUpdates
      case Some(existing) => incoming.maybeUpdated(existing, now) match {
        case None => existingUpdates
        case Some(updated) => updated :: existingUpdates
      }
    }
  }
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

  case class FlightsWithSplits(flightsToUpdate: List[ApiFlightWithSplits], arrivalsToRemove: List[Arrival]) extends PortStateMinutes {
    def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
      val updatedFlights = flightsToUpdate.map(_.copy(lastUpdated = Option(now)))

      portState.flights --= arrivalsToRemove.map(_.unique)
      portState.flights ++= updatedFlights.map(f => (f.apiFlight.unique, f))

      portStateDiff(updatedFlights)
    }

    def portStateDiff(updatedFlights: Seq[ApiFlightWithSplits]): PortStateDiff = {
      val removals = arrivalsToRemove.map(f => RemoveFlight(UniqueArrival(f)))
      val newDiff = PortStateDiff(removals, updatedFlights, Seq(), Seq(), Seq())
      newDiff
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

  val oneMinuteMillis: MillisSinceEpoch = 60000L

  case class PortStateError(message: String)

  object PortStateError {
    implicit val rw: RW[PortStateError] = macroRW
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
                         lastUpdated: Option[MillisSinceEpoch] = None) extends Minute with TerminalMinute with WithLastUpdated with MinuteComparison[StaffMinute] {
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

    override def maybeUpdated(existing: StaffMinute, now: MillisSinceEpoch): Option[StaffMinute] =
      if (existing.shifts != shifts || existing.fixedPoints != fixedPoints || existing.movements != movements) Option(existing.copy(
        shifts = shifts, fixedPoints = fixedPoints, movements = movements, lastUpdated = Option(now)
      ))
      else None
  }

  object StaffMinute {
    def empty = StaffMinute("", 0L, 0, 0, 0, None)

    implicit val rw: RW[StaffMinute] = macroRW
  }

  case class StaffMinutes(minutes: Seq[StaffMinute]) extends PortStateMinutes {
    def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
      val minutesDiff = minutes.foldLeft(List[StaffMinute]()) { case (soFar, sm) =>
        addIfUpdated(portState.staffMinutes.getByKey(sm.key), now, soFar, sm, () => sm.copy(lastUpdated = Option(now)))
      }
      portState.staffMinutes +++= minutesDiff
      PortStateDiff(Seq(), Seq(), Seq(), Seq(), minutesDiff)
    }
  }

  object StaffMinutes {
    def apply(minutesByKey: IMap[TM, StaffMinute]): StaffMinutes = StaffMinutes(minutesByKey.values.toSeq)

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
                          lastUpdated: Option[MillisSinceEpoch] = None) extends Minute with WithLastUpdated {
    def equals(candidate: CrunchMinute): Boolean = this.copy(lastUpdated = None) == candidate.copy(lastUpdated = None)

    lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)
  }

  object CrunchMinute {
    def apply(drm: DeskRecMinuteLike, now: MillisSinceEpoch): CrunchMinute = CrunchMinute(
      terminalName = drm.terminalName,
      queueName = drm.queueName,
      minute = drm.minute,
      paxLoad = drm.paxLoad,
      workLoad = drm.workLoad,
      deskRec = drm.deskRec,
      waitTime = drm.waitTime,
      lastUpdated = Option(now))

    def apply(sm: SimulationMinuteLike, now: MillisSinceEpoch): CrunchMinute = CrunchMinute(
      terminalName = sm.terminalName,
      queueName = sm.queueName,
      minute = sm.minute,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      deployedDesks = Option(sm.desks),
      deployedWait = Option(sm.waitTime),
      lastUpdated = Option(now))

    def apply(tqm: TQM, ad: DeskStat, now: MillisSinceEpoch): CrunchMinute = CrunchMinute(
      terminalName = tqm.terminalName,
      queueName = tqm.queueName,
      minute = tqm.minute,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      actDesks = ad.desks,
      actWait = ad.waitTime,
      lastUpdated = Option(now)
    )

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

  case class DeskStat(desks: Option[Int], waitTime: Option[Int]) extends MinuteComparison[CrunchMinute] {
    override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
      if (existing.actDesks != desks || existing.actWait != waitTime) Option(existing.copy(
        actDesks = desks, actWait = waitTime, lastUpdated = Option(now)
      ))
      else None
  }

  case class ActualDeskStats(portDeskSlots: IMap[String, IMap[String, IMap[MillisSinceEpoch, DeskStat]]]) extends PortStateMinutes {
    val oneMinuteMillis: Long = 60 * 1000

    def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
      val crunchMinutesDiff = minutes.foldLeft(List[CrunchMinute]()) { case (soFar, (key, dm)) =>
        addIfUpdated(portState.crunchMinutes.getByKey(key), now, soFar, dm, () => CrunchMinute(key, dm, now))
      }
      portState.crunchMinutes +++= crunchMinutesDiff
      val newDiff = PortStateDiff(Seq(), Seq(), Seq(), crunchMinutesDiff, Seq())

      newDiff
    }

    lazy val minutes: IMap[TQM, DeskStat] = for {
      (tn, queueMinutes) <- portDeskSlots
      (qn, deskStats) <- queueMinutes
      (startMinute, deskStat) <- deskStats
      minute <- startMinute until startMinute + 15 * oneMinuteMillis by oneMinuteMillis
    } yield (TQM(tn, qn, minute), deskStat)
  }

  case class CrunchMinutes(crunchMinutes: Set[CrunchMinute])

  case class PortStateUpdates(latest: MillisSinceEpoch, flights: Set[ApiFlightWithSplits], minutes: Set[CrunchMinute], staff: Set[StaffMinute])

  object PortStateUpdates {
    implicit val rw: RW[PortStateUpdates] = macroRW
  }

  case class ForecastTimeSlot(startMillis: MillisSinceEpoch, available: Int, required: Int)

  case class ForecastPeriodWithHeadlines(forecast: ForecastPeriod, headlines: ForecastHeadlineFigures)

  case class ForecastPeriod(days: IMap[MillisSinceEpoch, Seq[ForecastTimeSlot]])

  case class ForecastHeadlineFigures(queueDayHeadlines: Seq[QueueHeadline])

  case class QueueHeadline(day: MillisSinceEpoch, queue: QueueName, paxNos: Int, workload: Int)

  def groupCrunchMinutesByX(groupSize: Int)(crunchMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])], terminalName: TerminalName, queueOrder: List[String]): Seq[(MillisSinceEpoch, Seq[CrunchMinute])] = {
    crunchMinutes.grouped(groupSize).toList.map(group => {
      val byQueueName = group.flatMap(_._2).groupBy(_.queueName)
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


