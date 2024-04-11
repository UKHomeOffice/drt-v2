package drt.server.feeds.edi

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.server.feeds.Arriveable
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals
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

  override val maybeEstimated: Option[Long] = EstimatedDateTime.map(SDate(_).millisSinceEpoch)
  override val maybeEstimatedChox: Option[Long] = None
  override val terminal: Terminals.Terminal = A2
  override val runway: Option[String] = None

  val isNotFreight: Boolean = TerminalCode.toUpperCase == "T1"
  val isNotSecondaryCodeShare: Boolean = CodeSharePrimaryFlightId.isEmpty
  val isArrival: Boolean = DepartureArrivalType.toUpperCase == "A"

  override val isValid: Boolean = isArrival && isNotFreight && isNotSecondaryCodeShare
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
