package drt.shared.api

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.MilliTimes.oneMinuteMillis
import drt.shared.Terminals.Terminal
import drt.shared._
import upickle.default.{ReadWriter, macroRW}

import scala.collection.immutable.{List, NumericRange}
import scala.util.matching.Regex

case class FlightCodeSuffix(suffix: String)

case class Arrival(Operator: Option[Operator],
                   CarrierCode: CarrierCode,
                   VoyageNumber: VoyageNumber,
                   FlightCodeSuffix: Option[FlightCodeSuffix],
                   Status: ArrivalStatus,
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
                   AirportID: PortCode,
                   Terminal: Terminal,
                   Origin: PortCode,
                   Scheduled: MillisSinceEpoch,
                   PcpTime: Option[MillisSinceEpoch],
                   FeedSources: Set[FeedSource],
                   CarrierScheduled: Option[MillisSinceEpoch],
                   ApiPax: Option[Int],
                   ScheduledDeparture: Option[MillisSinceEpoch],
                  ) extends WithUnique[UniqueArrival] {
  val paxOffPerMinute = 20

  def suffixString: String = FlightCodeSuffix match {
    case None => ""
    case Some(s) => s.suffix
  }

  def displayStatus: ArrivalStatus = {

    val fifteenMinutes = 15 * 60 * 1000

    (this.Estimated, this.ActualChox, this.Actual) match {
      case (_, _, _) if isCancelledStatus(this.Status.description.toLowerCase) => ArrivalStatus("Cancelled")
      case (_, _, _) if isDivertedStatus(this.Status.description.toLowerCase) => ArrivalStatus("Diverted")
      case (_, Some(_), _) => ArrivalStatus("On Chocks")
      case (_, _, Some(_)) => ArrivalStatus("Landed")
      case (Some(e), _, _) if this.Scheduled + fifteenMinutes < e => ArrivalStatus("Delayed")
      case (Some(_), _, _) => ArrivalStatus("Expected")
      case (None, _, _) => ArrivalStatus("Scheduled")

    }
  }

  val isDivertedStatus: String => Boolean = description => description == "redirected" | description == "diverted"
  val isCancelledStatus: String => Boolean = description => description == "c" | description == "canceled" | description == "deleted / removed flight record" | description == "cancelled" | description.contains("deleted")

  val flightCode: FlightCode = FlightCode(CarrierCode, VoyageNumber, FlightCodeSuffix)

  def flightCodeString: String = flightCode.toString

  def basicForComparison: Arrival = copy(PcpTime = None)

  def equals(arrival: Arrival): Boolean = arrival.basicForComparison == basicForComparison

  lazy val uniqueId: Int = uniqueStr.hashCode
  lazy val uniqueStr: String = s"$Terminal$Scheduled${VoyageNumber.numeric}"

  def hasPcpDuring(start: SDateLike, end: SDateLike): Boolean = {
    val firstPcpMilli = PcpTime.getOrElse(0L)
    val lastPcpMilli = firstPcpMilli + millisToDisembark(ActPax.getOrElse(0), 20)
    val firstInRange = start.millisSinceEpoch <= firstPcpMilli && firstPcpMilli <= end.millisSinceEpoch
    val lastInRange = start.millisSinceEpoch <= lastPcpMilli && lastPcpMilli <= end.millisSinceEpoch
    firstInRange || lastInRange
  }

  def isRelevantToPeriod(rangeStart: SDateLike, rangeEnd: SDateLike): Boolean =
    Arrival.isRelevantToPeriod(rangeStart, rangeEnd)(this)

  def millisToDisembark(pax: Int, paxPerMinute: Int): Long = {
    val minutesToDisembark = (pax.toDouble / paxPerMinute).ceil
    val oneMinuteInMillis = 60 * 1000
    (minutesToDisembark * oneMinuteInMillis).toLong
  }

  val bestPcpPaxEstimate: Int = PcpPax.bestPcpPaxEstimate(this)

  def minutesOfPaxArrivals: Int = {
    val totalPax = bestPcpPaxEstimate
    if (totalPax <= 0) 0
    else (totalPax.toDouble / paxOffPerMinute).ceil.toInt - 1
  }

  lazy val pcpRange: NumericRange[MillisSinceEpoch] = {
    val pcpStart = MilliTimes.timeToNearestMinute(PcpTime.getOrElse(0L))
    val pcpEnd = pcpStart + oneMinuteMillis * minutesOfPaxArrivals
    pcpStart to pcpEnd by oneMinuteMillis
  }

  def paxDeparturesByMinute(departRate: Int): Iterable[(MillisSinceEpoch, Int)] = {
    val totalPax = bestPcpPaxEstimate
    val maybeRemainingPax = totalPax % departRate match {
      case 0 => None
      case someLeftovers => Option(someLeftovers)
    }
    val paxByMinute = List.fill(totalPax / departRate)(departRate) ::: maybeRemainingPax.toList
    pcpRange.zip(paxByMinute)
  }

  lazy val unique: UniqueArrival = UniqueArrival(VoyageNumber.numeric, Terminal, Scheduled, Origin)

  def isCancelled: Boolean = Status.description match {
    case st if st.toLowerCase.contains("cancelled") => true
    case st if st.toLowerCase.contains("canceled") => true
    case st if st.toLowerCase.contains("deleted") => true
    case _ => false
  }
}

object Arrival {
  val flightCodeRegex: Regex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]*)$".r

  def isInRange(rangeStart: MillisSinceEpoch, rangeEnd: MillisSinceEpoch)(needle: MillisSinceEpoch): Boolean =
    rangeStart < needle && needle < rangeEnd

  def isRelevantToPeriod(rangeStart: SDateLike, rangeEnd: SDateLike)(arrival: Arrival): Boolean = {
    val rangeCheck: MillisSinceEpoch => Boolean = isInRange(rangeStart.millisSinceEpoch, rangeEnd.millisSinceEpoch)

    rangeCheck(arrival.Scheduled) ||
      rangeCheck(arrival.Estimated.getOrElse(0)) ||
      rangeCheck(arrival.EstimatedChox.getOrElse(0)) ||
      rangeCheck(arrival.Actual.getOrElse(0)) ||
      rangeCheck(arrival.ActualChox.getOrElse(0)) ||
      arrival.hasPcpDuring(rangeStart, rangeEnd)
  }

  def summaryString(arrival: Arrival): String = arrival.AirportID + "/" + arrival.Terminal + "@" + arrival.Scheduled + "!" + arrival.flightCodeString

  def standardiseFlightCode(flightCode: String): String = {
    val flightCodeRegex = "^([A-Z0-9]{2,3}?)([0-9]{1,4})([A-Z]?)$".r

    flightCode match {
      case flightCodeRegex(operator, flightNumber, suffix) =>
        val number = f"${flightNumber.toInt}%04d"
        f"$operator$number$suffix"
      case _ => flightCode
    }
  }

  implicit val arrivalStatusRw: ReadWriter[ArrivalStatus] = macroRW
  implicit val voyageNumberRw: ReadWriter[VoyageNumber] = macroRW
  implicit val arrivalSuffixRw: ReadWriter[FlightCodeSuffix] = macroRW
  implicit val operatorRw: ReadWriter[Operator] = macroRW
  implicit val portCodeRw: ReadWriter[PortCode] = macroRW
  implicit val arrivalRw: ReadWriter[Arrival] = macroRW

  def apply(Operator: Option[Operator],
            Status: ArrivalStatus,
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
            AirportID: PortCode,
            Terminal: Terminal,
            rawICAO: String,
            rawIATA: String,
            Origin: PortCode,
            Scheduled: MillisSinceEpoch,
            PcpTime: Option[MillisSinceEpoch],
            FeedSources: Set[FeedSource],
            CarrierScheduled: Option[MillisSinceEpoch] = None,
            ApiPax: Option[Int] = None,
            ScheduledDeparture: Option[MillisSinceEpoch] = None
           ): Arrival = {
    val (carrierCode: CarrierCode, voyageNumber: VoyageNumber, maybeSuffix: Option[FlightCodeSuffix]) = {
      val bestCode = (rawIATA, rawICAO) match {
        case (iata, _) if iata != "" => iata
        case (_, icao) if icao != "" => icao
        case _ => ""
      }

      FlightCode.flightCodeToParts(bestCode)
    }

    Arrival(
      Operator = Operator,
      CarrierCode = carrierCode,
      VoyageNumber = voyageNumber,
      FlightCodeSuffix = maybeSuffix,
      Status = Status,
      Estimated = Estimated,
      Actual = Actual,
      EstimatedChox = EstimatedChox,
      ActualChox = ActualChox,
      Gate = Gate,
      Stand = Stand,
      MaxPax = MaxPax,
      ActPax = ActPax,
      TranPax = TranPax,
      RunwayID = RunwayID,
      BaggageReclaimId = BaggageReclaimId,
      AirportID = AirportID,
      Terminal = Terminal,
      Origin = Origin,
      Scheduled = Scheduled,
      PcpTime = PcpTime,
      FeedSources = FeedSources,
      CarrierScheduled = CarrierScheduled,
      ApiPax = ApiPax,
      ScheduledDeparture = ScheduledDeparture
    )
  }
}
