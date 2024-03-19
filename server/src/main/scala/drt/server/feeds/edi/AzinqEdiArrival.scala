package drt.server.feeds.edi

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.server.feeds.Arriveable
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.A2
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

  override def toArrival: FeedArrival = {
    val flightCode = AirlineIATA + FlightNumber
    val (carrierCode, voyageNumberLike, maybeSuffix) = FlightCode.flightCodeToParts(flightCode)
    val voyageNumber = voyageNumberLike match {
      case vn: VoyageNumber => vn
      case _ => throw new Exception(s"Failed to parse voyage number from $flightCode")
    }
    LiveArrival(
      operator = None,
      maxPax = MaxPax,
      totalPax = TotalPassengerCount,
      transPax = None,
      terminal = A2,
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = maybeSuffix.map(_.suffix),
      origin = OriginDestAirportIATA,
      scheduled = SDate(ScheduledDateTime).millisSinceEpoch,
      estimated = EstimatedDateTime.map(SDate(_).millisSinceEpoch),
      touchdown = ALDT.map(SDate(_).millisSinceEpoch),
      estimatedChox = None,
      actualChox = AIBT.map(SDate(_).millisSinceEpoch),
      status = FlightStatus,
      gate = GateCode,
      stand = StandCode,
      runway = None,
      baggageReclaim = CarouselCode,
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
