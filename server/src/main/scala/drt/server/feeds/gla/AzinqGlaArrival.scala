package drt.server.feeds.gla

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.server.feeds.Arriveable
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals
import uk.gov.homeoffice.drt.arrivals.LiveArrival
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

  override def toArrival: LiveArrival = arrivals.LiveArrival(
    operator = None,
    maxPax = MaxPax,
    totalPax = TotalPassengerCount,
    transPax = None,
    terminal = Terminal(TerminalCode),
    voyageNumber = FlightNumber.toInt,
    carrierCode = AirlineIATA,
    flightCodeSuffix = None,
    origin = OriginDestAirportIATA,
    scheduled = SDate(ScheduledDateTime).millisSinceEpoch,
    estimated = AODBProbableDateTime.map(SDate(_).millisSinceEpoch),
    touchdown = ALDT.map(SDate(_).millisSinceEpoch),
    estimatedChox = EIBT.map(SDate(_).millisSinceEpoch),
    actualChox = AIBT.map(SDate(_).millisSinceEpoch),
    status = FlightStatusDesc,
    gate = GateCode,
    stand = StandCode,
    runway = Runway,
    baggageReclaim = CarouselCode,
  )
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

