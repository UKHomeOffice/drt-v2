package drt.server.feeds.gla

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import drt.server.feeds.Arriveable
import drt.server.feeds.Implicits._
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.LiveFeedSource
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

  override def toArrival: Arrival = Arrival(
    None,
    Status = FlightStatusDesc,
    Estimated = AODBProbableDateTime.map(SDate(_).millisSinceEpoch),
    Predictions = Predictions(0L, Map()),
    Actual = ALDT.map(SDate(_).millisSinceEpoch),
    EstimatedChox = EIBT.map(SDate(_).millisSinceEpoch),
    ActualChox = AIBT.map(SDate(_).millisSinceEpoch),
    Gate = GateCode,
    Stand = StandCode,
    MaxPax = MaxPax,
    RunwayID = Runway,
    BaggageReclaimId = CarouselCode,
    AirportID = "GLA",
    Terminal = Terminal(TerminalCode),
    rawIATA = AirlineIATA + FlightNumber,
    rawICAO = AirlineICAO + FlightNumber,
    Origin = OriginDestAirportIATA,
    Scheduled = SDate(ScheduledDateTime).millisSinceEpoch,
    PcpTime = None,
    FeedSources = Set(LiveFeedSource),
    PassengerSources = Map(LiveFeedSource -> Passengers(TotalPassengerCount, None))
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

