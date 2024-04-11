package drt.server.feeds.gla

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.server.feeds.Arriveable
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate


case class AzinqGlaArrival(AIBT: Option[String],
                           AirlineIATA: String,
                           AirlineICAO: String,
                           ALDT: Option[String],
                           AODBProbableDateTime: Option[String],
                           CarouselCode: Option[String],
                           DepartureArrivalType: String,
                           EIBT: Option[String],
                           FlightNumber: String,
                           FlightStatusDesc: String,
                           GateCode: Option[String],
                           MaxPax: Option[Int],
                           OriginDestAirportIATA: String,
                           Runway: Option[String],
                           ScheduledDateTime: String,
                           StandCode: Option[String],
                           TerminalCode: String,
                           TotalPassengerCount: Option[Int]
                          ) extends Arriveable {

  override val isValid: Boolean = DepartureArrivalType == "A"

  override val maybeEstimated: Option[Long] = AODBProbableDateTime.map(SDate(_).millisSinceEpoch)
  override val maybeEstimatedChox: Option[Long] = EIBT.map(SDate(_).millisSinceEpoch)
  override val terminal: Terminal = Terminal(TerminalCode)
  override val FlightStatus: String = FlightStatusDesc
  override val runway: Option[String] = Runway
}

object AzinqGlaArrivalJsonFormats extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val glaArrivalFormat: RootJsonFormat[AzinqGlaArrival] = jsonFormat(
    AzinqGlaArrival.apply,
    "AIBT",
    "AirlineIATA",
    "AirlineICAO",
    "ALDT",
    "AODBProbableDateTime",
    "CarouselCode",
    "DepartureArrivalType",
    "EIBT",
    "FlightNumber",
    "FlightStatusDesc",
    "GateCode",
    "MaxPax",
    "OriginDestAirportIATA",
    "Runway",
    "ScheduledDateTime",
    "StandCode",
    "TerminalCode",
    "TotalPassengerCount",
  )
}

