package drt.server.feeds.edi

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.server.feeds.Arriveable
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.A2
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate


case class AzinqEdiArrival(AIBT: Option[String],
                           AirlineIATA: String,
                           ALDT: Option[String],
                           CarouselCode: Option[String],
                           CodeSharePrimaryFlightId: Option[Int],
                           DepartureArrivalType: String,
                           EstimatedDateTime: Option[String],
                           FlightNumber: String,
                           FlightStatus: String,
                           GateCode: Option[String],
                           MaxPax: Option[Int],
                           OriginDestAirportIATA: String,
                           ScheduledDateTime: String,
                           StandCode: Option[String],
                           TerminalCode: String,
                           TotalPassengerCount: Option[Int],
                          ) extends Arriveable {

  private val isArrival: Boolean = DepartureArrivalType.toUpperCase == "A"
  private val isNotFreight: Boolean = TerminalCode.toUpperCase == "T1"
  private val isNotSecondaryCodeShare: Boolean = CodeSharePrimaryFlightId.isEmpty

  override val isValid: Boolean = isArrival && isNotFreight && isNotSecondaryCodeShare

  def toArrival: Arrival = {
    val feedSources: Set[FeedSource] = Set(LiveFeedSource)
    val passengerSources: Map[FeedSource, Passengers] = Map(LiveFeedSource -> Passengers(TotalPassengerCount, None))
    val flightCode = AirlineIATA + FlightNumber
    val (carrierCode, voyageNumberLike, maybeSuffix) = FlightCode.flightCodeToParts(flightCode)
    val voyageNumber = voyageNumberLike match {
      case vn: VoyageNumber => vn
      case _ => throw new Exception(s"Failed to parse voyage number from $flightCode")
    }
    Arrival(
      Operator = None,
      CarrierCode = carrierCode,
      VoyageNumber = voyageNumber,
      FlightCodeSuffix = maybeSuffix,
      Status = ArrivalStatus(FlightStatus),
      Estimated = EstimatedDateTime.map(SDate(_).millisSinceEpoch),
      Predictions = Predictions(0L, Map()),
      Actual = ALDT.map(SDate(_).millisSinceEpoch),
      EstimatedChox = None,
      ActualChox = AIBT.map(SDate(_).millisSinceEpoch),
      Gate = GateCode,
      Stand = StandCode,
      MaxPax = MaxPax,
      RunwayID = None,
      BaggageReclaimId = CarouselCode,
      AirportID = PortCode("EMA"),
      Terminal = A2,
      Origin = PortCode(OriginDestAirportIATA),
      Scheduled = SDate(ScheduledDateTime).millisSinceEpoch,
      PcpTime = None,
      FeedSources = feedSources,
      CarrierScheduled = None,
      ScheduledDeparture = None,
      RedListPax = None,
      PassengerSources = passengerSources,
    )
  }
}

object AzinqEdiArrivalJsonFormats extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val azinqEdiArrivalJsonFormat: RootJsonFormat[AzinqEdiArrival] = jsonFormat(
    AzinqEdiArrival.apply,
    "AIBT",
    "AirlineIATA",
    "ALDT",
    "CarouselCode",
    "CodeSharePrimaryFlightId",
    "DepartureArrivalType",
    "EstimatedDateTime",
    "FlightNumber",
    "FlightStatus",
    "GateCode",
    "MaxPax",
    "OriginDestAirportIATA",
    "ScheduledDateTime",
    "StandCode",
    "TerminalCode",
    "TotalPassengerCount",
  )
}
