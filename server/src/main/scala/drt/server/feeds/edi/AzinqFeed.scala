package drt.server.feeds.edi

import akka.actor.typed.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.A2
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}

case class AzinqEdiArrival(AIBT: Option[String],
                           AirlineIATA: String,
                           ALDT: Option[String],
                           CarouselCode: Option[String],
                           CodeSharePrimaryFlightId: Option[Int],
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
                          ) {
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

trait AzinqArrivalEdiJsonFormats extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val azinqEdiArrivalJsonFormat: RootJsonFormat[AzinqEdiArrival] = jsonFormat15(AzinqEdiArrival)
}

object AzinqFeed extends AzinqArrivalEdiJsonFormats {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def source(source: Source[FeedTick, ActorRef[FeedTick]],
             fetchArrivals: () => Future[Seq[Arrival]],
            )
            (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, ActorRef[FeedTick]] =
    source.mapAsync(1)(_ => {
      log.info(s"Requesting live feed.")
      fetchArrivals()
        .map(arrivals => ArrivalsFeedSuccess(Flights(arrivals)))
        .recover {
          case t =>
            log.error("Failed to fetch arrivals", t)
            ArrivalsFeedFailure("Failed to fetch arrivals")
        }
    })


  def apply(uri: String,
            username: String,
            password: String,
            token: String,
            httpRequest: HttpRequest => Future[HttpResponse],
           )
           (implicit ec: ExecutionContext, mat: Materializer): () => Future[Seq[Arrival]] = {
    val request = HttpRequest(
      uri = uri,
      headers = List(
        RawHeader("token", token),
        RawHeader("username", username),
        RawHeader("password", password),
      ))

    () =>
      httpRequest(request)
        .flatMap(Unmarshal[HttpResponse](_).to[List[AzinqEdiArrival]])
        .map(_.filter(a => a.TerminalCode != "FRT" && a.CodeSharePrimaryFlightId.isEmpty).map(_.toArrival))
  }
}
