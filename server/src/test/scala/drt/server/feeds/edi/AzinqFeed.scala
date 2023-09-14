package drt.server.feeds.edi

import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import org.specs2.mutable.Specification
import spray.json.DefaultJsonProtocol._
import spray.json.RootJsonFormat
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.A2
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}

case class AzinqEdiArrival(FlightNumber: String,
                           AirlineIATA: String,
                           OriginDestAirportIATA: String,
                           ScheduledDateTime: String,
                           EstimatedDateTime: Option[String],
                           ALDT: Option[String],
                           AIBT: Option[String],
                           CarouselCode: Option[String],
                           FlightStatus: String,
                           GateCode: Option[String],
                           StandCode: Option[String],
                           MaxPax: Option[Int],
                           TotalPassengerCount: Option[Int],
                          ) {
  def toArrival: Arrival = {
    val feedSources: Set[FeedSource] = Set(LiveFeedSource)
    val passengerSources: Map[FeedSource, Passengers] = Map(LiveFeedSource -> Passengers(TotalPassengerCount, None))
    Arrival(
      Operator = None,
      CarrierCode = CarrierCode(AirlineIATA),
      VoyageNumber = VoyageNumber(FlightNumber.toInt),
      FlightCodeSuffix = None,
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

object AzinqArrivalEdiJsonFormats {
  implicit val azinqEdiArrivalJsonFormat: RootJsonFormat[AzinqEdiArrival] = jsonFormat13(AzinqEdiArrival)
}

object AzinqFeed {
  val url = "https://edi.azinqairportapi.com/v1/getdata/flights"

  def apply(username: String, password: String, token: String, httpRequest: HttpRequest => Future[HttpResponse])
           (implicit ec: ExecutionContext): () => Future[Seq[Arrival]] = {
    val request = HttpRequest(uri = url, headers = List(
      RawHeader("token", token),
      RawHeader("username", username),
      RawHeader("password", password),
    ))

    () => httpRequest(request).map(_ => Seq())
  }
}

class AzinqFeed extends Specification {
  ""
}
