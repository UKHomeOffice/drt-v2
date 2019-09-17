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

import scala.collection.{Map, SortedMap, mutable}
import scala.collection.immutable.{Map => IMap, SortedMap => ISortedMap}
import scala.collection.mutable.{Map => MMap, SortedMap => MSortedMap}
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

case class ApiFlightWithSplits(apiFlight: Arrival, splits: Set[Splits], lastUpdated: Option[MillisSinceEpoch] = None) extends WithUnique[UniqueArrival] {
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

  def atTime: MillisSinceEpoch => TQM = (time: MillisSinceEpoch) => TQM("", "", time)
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

  def atTime: MillisSinceEpoch => TM = (time: MillisSinceEpoch) => TM("", time)
}

trait WithLegacyUniqueId[LI, I] extends Ordered[I] {
  def uniqueId: LI
}

trait WithUnique[I] {
  def unique: I
}

case class UniqueArrival(number: Int, terminalName: TerminalName, scheduled: MillisSinceEpoch) extends WithLegacyUniqueId[Int, UniqueArrival] with WithTimeAccessor {
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
}

object UniqueArrival {
  implicit val rw: RW[UniqueArrival] = macroRW

  def apply(arrival: Arrival): UniqueArrival = UniqueArrival(arrival.flightNumber, arrival.Terminal, arrival.Scheduled)

  def atTime: MillisSinceEpoch => UniqueArrival = (time: MillisSinceEpoch) => UniqueArrival(0, "", time)
}

case class CodeShareKey(scheduled: Long, terminalName: TerminalName, origin: String, arrivalKeys: Set[ArrivalKey]) extends Ordered[CodeShareKey] {
  lazy val comparisonStringForEquality = s"$scheduled-$terminalName-$origin"

  lazy val comparisonStringForOrdering = s"${100 - arrivalKeys.size}-$scheduled-$terminalName-$origin"

  override def equals(o: scala.Any): Boolean = o match {
    case o: CodeShareKey => o.comparisonStringForEquality == comparisonStringForEquality
    case _ => false
  }

  override def compare(that: CodeShareKey): Int =
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
                    FlightID: Option[Int],
                    AirportID: String,
                    Terminal: String,
                    rawICAO: String,
                    rawIATA: String,
                    Origin: String,
                    Scheduled: MillisSinceEpoch,
                    PcpTime: Option[MillisSinceEpoch],
                    FeedSources: Set[FeedSource],
                    LastKnownPax: Option[Int] = None) extends WithUnique[UniqueArrival] {
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

case object CiriumFeedSource extends FeedSource

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

  def atTime: MillisSinceEpoch => ArrivalKey = (time: MillisSinceEpoch) => ArrivalKey("", "", time)
}

case class ArrivalsDiff(toUpdate: ISortedMap[ArrivalKey, Arrival], toRemove: Set[Arrival])

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

case class PortStateDiff(flightRemovals: Seq[RemoveFlight],
                         flightUpdates: IMap[UniqueArrival, ApiFlightWithSplits],
                         crunchMinuteUpdates: IMap[TQM, CrunchMinute],
                         staffMinuteUpdates: IMap[TM, StaffMinute]) {
  val isEmpty: Boolean = flightRemovals.isEmpty && flightUpdates.isEmpty && crunchMinuteUpdates.isEmpty && staffMinuteUpdates.isEmpty
}

object PortStateDiff {
  def apply(flightRemovals: Seq[RemoveFlight], flightUpdates: Seq[ApiFlightWithSplits], crunchUpdates: Seq[CrunchMinute], staffUpdates: Seq[StaffMinute]): PortStateDiff = PortStateDiff(
    flightRemovals = flightRemovals,
    flightUpdates = flightUpdates.map(fws => (fws.apiFlight.unique, fws)).toMap,
    crunchMinuteUpdates = crunchUpdates.map(cm => (TQM(cm), cm)).toMap,
    staffMinuteUpdates = staffUpdates.map(sm => (TM(sm), sm)).toMap
  )
}

trait PortStateMinutes {
  def applyTo(portStateMutable: PortStateMutable, now: MillisSinceEpoch): PortStateDiff
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

  case class FlightsWithSplits(flightsToUpdate: Seq[ApiFlightWithSplits], arrivalsToRemove: Seq[Arrival]) extends PortStateMinutes {
    def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
      val updatedFlights = flightsToUpdate.map(_.copy(lastUpdated = Option(now)))

      portState.flights --= arrivalsToRemove.map(_.unique)
      portState.flights ++= updatedFlights.map(f => (f.apiFlight.unique, f))

      portStateDiff(updatedFlights)
    }

    def portStateDiff(updatedFlights: Seq[ApiFlightWithSplits]): PortStateDiff = {
      val removals = arrivalsToRemove.map(f => RemoveFlight(UniqueArrival(f)))
      val newDiff = PortStateDiff(removals, updatedFlights, Seq(), Seq())
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

  sealed trait PortStateLike {
    val flights: Map[UniqueArrival, ApiFlightWithSplits]
    val crunchMinutes: SortedMap[TQM, CrunchMinute]
    val staffMinutes: SortedMap[TM, StaffMinute]

    lazy val latestUpdate: MillisSinceEpoch = {
      val latestFlights = if (flights.nonEmpty) flights.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
      val latestCrunch = if (crunchMinutes.nonEmpty) crunchMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
      val latestStaff = if (staffMinutes.nonEmpty) staffMinutes.map(_._2.lastUpdated.getOrElse(0L)).max else 0L
      List(latestFlights, latestCrunch, latestStaff).max
    }

    def window(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState

    def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState
  }

  case class PortState(flights: ISortedMap[UniqueArrival, ApiFlightWithSplits],
                       crunchMinutes: ISortedMap[TQM, CrunchMinute],
                       staffMinutes: ISortedMap[TM, StaffMinute]) extends PortStateLike {
    def window(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState = {
      val roundedStart = start.roundToMinute()
      val roundedEnd = end.roundToMinute()

      val fs = flightsRange(roundedStart, roundedEnd)
      val cms = crunchMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
      val sms = staffMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

      PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
    }

    def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState = {
      val roundedStart = start.roundToMinute()
      val roundedEnd = end.roundToMinute()

      val fs = flightsRangeWithTerminals(roundedStart, roundedEnd, portQueues)
      val cms = crunchMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
      val sms = staffMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

      PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
    }

    def purgeOlderThanDate(thresholdMillis: MillisSinceEpoch): PortState = PortState(
      purgeExpired(flights, UniqueArrival.atTime, thresholdMillis),
      purgeExpired(crunchMinutes, TQM.atTime, thresholdMillis),
      purgeExpired(staffMinutes, TM.atTime, thresholdMillis)
    )

    def purgeExpired[A <: WithTimeAccessor, B](expireable: ISortedMap[A, B], atTime: MillisSinceEpoch => A, thresholdMillis: MillisSinceEpoch): ISortedMap[A, B] = {
      expireable -- expireable.range(atTime(0L), atTime(thresholdMillis - 1)).keys
    }

    def flightsRange(roundedStart: SDateLike, roundedEnd: SDateLike): ISortedMap[UniqueArrival, ApiFlightWithSplits] = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
      .range(UniqueArrival.atTime(roundedStart.millisSinceEpoch), UniqueArrival.atTime(roundedEnd.millisSinceEpoch))

    def flightsRangeWithTerminals(roundedStart: SDateLike, roundedEnd: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[UniqueArrival, ApiFlightWithSplits] = flightsRange(roundedStart, roundedEnd)
      .filter { case (_, fws) => portQueues.contains(fws.apiFlight.Terminal) }

    def crunchMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[TQM, CrunchMinute] = crunchMinutes
      .range(TQM.atTime(startMillis), TQM.atTime(endMillis))

    def crunchMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[TQM, CrunchMinute] =
      crunchMinuteRange(startMillis, endMillis, portQueues)
        .filterKeys { tqm => portQueues.contains(tqm.terminalName) && portQueues(tqm.terminalName).contains(tqm.queueName) }

    def staffMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): ISortedMap[TM, StaffMinute] = staffMinutes
      .range(TM.atTime(startMillis), TM.atTime(endMillis))

    def staffMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): ISortedMap[TM, StaffMinute] =
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

    def crunchSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: TerminalName, queues: List[String]): ISortedMap[Long, IMap[String, CrunchMinute]] = {
      val startMillis = start.roundToMinute().millisSinceEpoch
      val endMillis = startMillis + (periods * periodSize * 60000)
      val periodMillis = periodSize * 60000
      ISortedMap[Long, IMap[String, CrunchMinute]]() ++ (startMillis until endMillis by periodMillis)
        .map { periodStart =>
          val queueMinutes = queues
            .map { queue =>
              val slotMinutes = (periodStart until (periodStart + periodMillis) by 60000)
                .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
                .collect { case Some(cm) => cm }
                .toList
              (queue, crunchPeriodSummary(terminal, periodStart, queue, slotMinutes))
            }
            .toMap
          (periodStart, queueMinutes)
        }
        .toMap
    }

    def staffSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: TerminalName): ISortedMap[Long, StaffMinute] = {
      val startMillis = start.roundToMinute().millisSinceEpoch
      val endMillis = startMillis + (periods * periodSize * 60000)
      val periodMillis = periodSize * 60000
      ISortedMap[Long, StaffMinute]() ++ (startMillis until endMillis by periodMillis)
        .map { periodStart =>
          val periodEnd = periodStart + periodMillis
          val slotMinutes = (periodStart until periodEnd by 60000)
            .map { minute => staffMinutes.get(TM(terminal, minute)) }
            .collect { case Some(cm) => cm }
            .toList
          val terminalMinutes = staffPeriodSummary(terminal, periodStart, slotMinutes)
          (periodStart, terminalMinutes)
        }
        .toMap
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
        deployedDesks = if (slotMinutes.exists(cm => cm.deployedDesks.isDefined)) Option(slotMinutes.map(_.deployedDesks.getOrElse(0)).max) else None,
        deployedWait = if (slotMinutes.exists(cm => cm.deployedWait.isDefined)) Option(slotMinutes.map(_.deployedWait.getOrElse(0)).max) else None,
        actDesks = if (slotMinutes.exists(cm => cm.actDesks.isDefined)) Option(slotMinutes.map(_.actDesks.getOrElse(0)).max) else None,
        actWait = if (slotMinutes.exists(cm => cm.actWait.isDefined)) Option(slotMinutes.map(_.actWait.getOrElse(0)).max) else None)
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

    def mutable: PortStateMutable = new PortStateMutable(
      MSortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights.map { case (_, fws) => (fws.apiFlight.unique, fws) },
      MSortedMap[TQM, CrunchMinute]() ++ crunchMinutes,
      MSortedMap[TM, StaffMinute]() ++ staffMinutes)
  }

  class PortStateMutable(val flights: MSortedMap[UniqueArrival, ApiFlightWithSplits],
                         val crunchMinutes: MSortedMap[TQM, CrunchMinute],
                         val staffMinutes: MSortedMap[TM, StaffMinute]) {

    def window(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState = {
      val roundedStart = start.roundToMinute()
      val roundedEnd = end.roundToMinute()

      val fs = flightsRange(roundedStart, roundedEnd)
      val cms = crunchMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
      val sms = staffMinuteRange(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

      PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
    }

    def windowWithTerminalFilter(start: SDateLike, end: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): PortState = {
      val roundedStart = start.roundToMinute()
      val roundedEnd = end.roundToMinute()

      val fs = flightsRangeWithTerminals(roundedStart, roundedEnd, portQueues)
      val cms = crunchMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues)
      val sms = staffMinuteRangeWithTerminals(roundedStart.millisSinceEpoch, roundedEnd.millisSinceEpoch, portQueues.keys.toSeq)

      PortState(flights = fs, crunchMinutes = cms, staffMinutes = sms)
    }

    def flightsRange(roundedStart: SDateLike, roundedEnd: SDateLike): ISortedMap[UniqueArrival, ApiFlightWithSplits] = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
      .range(UniqueArrival.atTime(roundedStart.millisSinceEpoch), UniqueArrival.atTime(roundedEnd.millisSinceEpoch))

    def flightsRangeWithTerminals(roundedStart: SDateLike, roundedEnd: SDateLike, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[UniqueArrival, ApiFlightWithSplits] = flightsRange(roundedStart, roundedEnd)
      .filter { case (_, fws) => portQueues.contains(fws.apiFlight.Terminal) }

    def purgeOlderThanDate(thresholdMillis: MillisSinceEpoch): Unit = {
      purgeExpired(flights, UniqueArrival.atTime, thresholdMillis)
      purgeExpired(crunchMinutes, TQM.atTime, thresholdMillis)
      purgeExpired(staffMinutes, TM.atTime, thresholdMillis)
    }

    def purgeExpired[A <: WithTimeAccessor, B](expireable: mutable.SortedMap[A, B], atTime: MillisSinceEpoch => A, thresholdMillis: MillisSinceEpoch): Unit = {
      val expired = expireable.range(atTime(0L), atTime(thresholdMillis - 1))
      expireable --= expired.keys
    }

    def crunchMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[TQM, CrunchMinute] =
      ISortedMap[TQM, CrunchMinute]() ++ crunchMinutes
        .range(TQM.atTime(startMillis), TQM.atTime(endMillis))

    def crunchMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, portQueues: IMap[TerminalName, Seq[QueueName]]): ISortedMap[TQM, CrunchMinute] =
      crunchMinuteRange(startMillis, endMillis, portQueues)
        .filterKeys { tqm => portQueues.contains(tqm.terminalName) && portQueues(tqm.terminalName).contains(tqm.queueName) }

    private def staffMinuteRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): ISortedMap[TM, StaffMinute] =
      ISortedMap[TM, StaffMinute]() ++ staffMinutes.range(TM.atTime(startMillis), TM.atTime(endMillis))

    private def staffMinuteRangeWithTerminals(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, terminals: Seq[TerminalName]): ISortedMap[TM, StaffMinute] =
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

    def crunchSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: TerminalName, queues: List[String]): MSortedMap[Long, IMap[String, CrunchMinute]] = {
      val startMillis = start.roundToMinute().millisSinceEpoch
      val endMillis = startMillis + (periods * periodSize * 60000)
      val periodMillis = periodSize * 60000
      MSortedMap[Long, IMap[String, CrunchMinute]]() ++ (startMillis until endMillis by periodMillis)
        .map { periodStart =>
          val queueMinutes = queues
            .map { queue =>
              val slotMinutes = (periodStart until (periodStart + periodMillis) by 60000)
                .map { minute => crunchMinutes.get(TQM(terminal, queue, minute)) }
                .collect { case Some(cm) => cm }
                .toList
              (queue, crunchPeriodSummary(terminal, periodStart, queue, slotMinutes))
            }
            .toMap
          (periodStart, queueMinutes)
        }
        .toMap
    }

    def staffSummary(start: SDateLike, periods: Long, periodSize: Long, terminal: TerminalName): MSortedMap[Long, StaffMinute] = {
      val startMillis = start.roundToMinute().millisSinceEpoch
      val endMillis = startMillis + (periods * periodSize * 60000)
      val periodMillis = periodSize * 60000
      MSortedMap[Long, StaffMinute]() ++ (startMillis until endMillis by periodMillis)
        .map { periodStart =>
          val periodEnd = periodStart + periodMillis
          val slotMinutes = (periodStart until periodEnd by 60000)
            .map { minute => staffMinutes.get(TM(terminal, minute)) }
            .collect { case Some(cm) => cm }
            .toList
          val terminalMinutes = staffPeriodSummary(terminal, periodStart, slotMinutes)
          (periodStart, terminalMinutes)
        }
        .toMap
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
        deployedDesks = if (slotMinutes.exists(cm => cm.deployedDesks.isDefined)) Option(slotMinutes.map(_.deployedDesks.getOrElse(0)).max) else None,
        deployedWait = if (slotMinutes.exists(cm => cm.deployedWait.isDefined)) Option(slotMinutes.map(_.deployedWait.getOrElse(0)).max) else None,
        actDesks = if (slotMinutes.exists(cm => cm.actDesks.isDefined)) Option(slotMinutes.map(_.actDesks.getOrElse(0)).max) else None,
        actWait = if (slotMinutes.exists(cm => cm.actWait.isDefined)) Option(slotMinutes.map(_.actWait.getOrElse(0)).max) else None)
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

    def applyFlightsWithSplitsDiff(flightRemovals: Seq[UniqueArrival], flightUpdates: Seq[ApiFlightWithSplits], nowMillis: MillisSinceEpoch): Unit = {
      flights --= flightRemovals
      flights ++= flightUpdates.map(f => (f.apiFlight.unique, f.copy(lastUpdated = Option(nowMillis))))
    }

    def applyCrunchDiff(crunchMinuteUpdates: Seq[CrunchMinute], nowMillis: MillisSinceEpoch): Unit = {
      crunchMinutes ++= crunchMinuteUpdates.map(cm => (cm.key, cm.copy(lastUpdated = Option(nowMillis))))
    }

    def applyStaffDiff(staffMinuteUpdates: Seq[StaffMinute], nowMillis: MillisSinceEpoch): Unit = {
      staffMinutes ++= staffMinuteUpdates.map(sm => (sm.key, sm.copy(lastUpdated = Option(nowMillis))))
    }

    def immutable: PortState = {
      val fs = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flights
      val cms = ISortedMap[TQM, CrunchMinute]() ++ crunchMinutes
      val sms = ISortedMap[TM, StaffMinute]() ++ staffMinutes
      PortState(fs, cms, sms)
    }

    def clear: Unit = {
      flights.clear
      crunchMinutes.clear
      staffMinutes.clear
    }
  }

  object PortState {
    implicit val rw: ReadWriter[PortState] =
      readwriter[(IMap[UniqueArrival, ApiFlightWithSplits], IMap[TQM, CrunchMinute], IMap[TM, StaffMinute])]
        .bimap[PortState](ps => portStateToTuple(ps), t => tupleToPortState(t))

    private def tupleToPortState(t: (IMap[UniqueArrival, ApiFlightWithSplits], IMap[TQM, CrunchMinute], IMap[TM, StaffMinute])): PortState = {
      PortState(ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ t._1, ISortedMap[TQM, CrunchMinute]() ++ t._2, ISortedMap[TM, StaffMinute]() ++ t._3)
    }

    private def portStateToTuple(ps: PortState): (ISortedMap[UniqueArrival, ApiFlightWithSplits], ISortedMap[TQM, CrunchMinute], ISortedMap[TM, StaffMinute]) = {
      (ps.flights, ps.crunchMinutes, ps.staffMinutes)
    }

    def apply(flightsWithSplits: List[ApiFlightWithSplits], crunchMinutes: List[CrunchMinute], staffMinutes: List[StaffMinute]): PortState = {
      val flights = ISortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flightsWithSplits.map(fws => (fws.apiFlight.unique, fws))
      val cms = ISortedMap[TQM, CrunchMinute]() ++ crunchMinutes.map(cm => (TQM(cm), cm))
      val sms = ISortedMap[TM, StaffMinute]() ++ staffMinutes.map(sm => (TM(sm), sm))
      PortState(flights, cms, sms)
    }

    val empty: PortState = PortState(ISortedMap[UniqueArrival, ApiFlightWithSplits](), ISortedMap[TQM, CrunchMinute](), ISortedMap[TM, StaffMinute]())
  }

  object PortStateMutable {
    def empty: PortStateMutable = new PortStateMutable(MSortedMap[UniqueArrival, ApiFlightWithSplits](), MSortedMap[TQM, CrunchMinute](), MSortedMap[TM, StaffMinute]())
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
    def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {

      val updatedMinutes = minutes.map(_.copy(lastUpdated = Option(now)))

      val diff = PortStateDiff(Seq(), Seq(), Seq(), updatedMinutes)

      updatedMinutes.foreach { updatedSm => portState.staffMinutes += (updatedSm.key -> updatedSm) }

      diff
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

    def apply(tqm: TQM, ad: DeskStat): CrunchMinute = CrunchMinute(
      terminalName = tqm.terminalName,
      queueName = tqm.queueName,
      minute = tqm.minute,
      paxLoad = 0,
      workLoad = 0,
      deskRec = 0,
      waitTime = 0,
      actDesks = ad.desks,
      actWait = ad.waitTime
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

  case class DeskStat(desks: Option[Int], waitTime: Option[Int])

  case class ActualDeskStats(portDeskSlots: IMap[String, IMap[String, IMap[MillisSinceEpoch, DeskStat]]]) extends PortStateMinutes {
    val oneMinuteMillis: Long = 60 * 1000

    def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
      val crunchMinutesDiff = minutes.foldLeft(List[CrunchMinute]()) { case (soFar, (key, dm)) =>
        val merged = mergeMinute(key, portState.crunchMinutes.get(key), dm, now)
        portState.crunchMinutes += (key -> merged)
        merged :: soFar
      }
      val newDiff = PortStateDiff(Seq(), Seq(), crunchMinutesDiff, Seq())

      newDiff
    }

    lazy val minutes: IMap[TQM, DeskStat] = for {
      (tn, queueMinutes) <- portDeskSlots
      (qn, deskStats) <- queueMinutes
      (startMinute, deskStat) <- deskStats
      minute <- startMinute until startMinute + 15 * oneMinuteMillis by oneMinuteMillis
    } yield (TQM(tn, qn, minute), deskStat)

    def newCrunchMinutes: ISortedMap[TQM, CrunchMinute] = ISortedMap[TQM, CrunchMinute]() ++ minutes
      .map { case (tqm, ad) => (tqm, CrunchMinute(tqm, ad)) }

    def mergeMinute(tqm: TQM, maybeMinute: Option[CrunchMinute], updatedDeskStat: DeskStat, now: MillisSinceEpoch): CrunchMinute = maybeMinute
      .map(existingCm => existingCm.copy(
        actDesks = updatedDeskStat.desks,
        actWait = updatedDeskStat.waitTime
      ))
      .getOrElse(CrunchMinute(tqm, updatedDeskStat))
      .copy(lastUpdated = Option(now))
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


