package drt.server.feeds.gla

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.gla.GlaFeed.GlaArrival
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.Terminal
import drt.shared.{Arrival, LiveFeedSource}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait GlaFeedRequesterLike {

  def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse]
}

object ProdGlaFeedRequester extends GlaFeedRequesterLike {

  override def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse] = Http().singleRequest(request)
}

case class GlaFeed(uri: String,
                   token: String,
                   password: String,
                   username: String,
                   feedRequester: GlaFeedRequesterLike)
                  (implicit system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) {

  import GlaFeed.JsonSupport._

  val log: Logger = LoggerFactory.getLogger(getClass)

  def tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source
    .tick(initialDelay = 1 milliseconds, interval = 60 seconds, tick = NotUsed)
    .mapAsync(1)(_ => {
      log.info(s"Requesting live feed.")
      requestArrivals()
    })

  def requestArrivals(): Future[ArrivalsFeedResponse] = feedRequester
    .send(
      HttpRequest(
        method = HttpMethods.GET,
        uri = Uri(uri),
        headers = List(
          RawHeader("token", s"$token"),
          RawHeader("password", s"$password"),
          RawHeader("username", s"$username")
        ),
        entity = HttpEntity.Empty
      ))
    .map { res =>
      Unmarshal[HttpResponse](res).to[List[GlaArrival]]
    }
    .flatten
    .map(glaArrivals => glaArrivals
      .filter(_.DepartureArrivalType == "A")
      .map(GlaFeed.toArrival)
    )
    .map(as => ArrivalsFeedSuccess(Flights(as)))
    .recover {
      case e: Exception =>
        log.warn(s"Failed to get Arrivals from GLA Live Feed", e)
        ArrivalsFeedFailure(s"Failed to get live arrivals ${e.getMessage}")
    }
}

object GlaFeed {

  case class GlaArrival(
                         AIBT: Option[String],
                         AirlineIATA: String,
                         AirlineICAO: String,
                         ALDT: Option[String],
                         AODBProbableDateTime: Option[String],
                         CarouselCode: Option[String],
                         CodeShareFlights: Option[String],
                         CodeShareInd: Option[String],
                         DepartureArrivalType: String,
                         EIBT: Option[String],
                         FlightNumber: String,
                         FlightStatus: String,
                         FlightStatusDesc: String,
                         GateCode: Option[String],
                         MaxPax: Option[Int],
                         OriginDestAirportIATA: String,
                         PaxEstimated: Option[Int],
                         Runway: Option[String],
                         ScheduledDateTime: String,
                         StandCode: Option[String],
                         TerminalCode: String,
                         TotalPassengerCount: Option[Int]
                       )

  def toArrival(ga: GlaArrival): Arrival = Arrival(
    None,
    Status = ga.FlightStatusDesc,
    Estimated = ga.AODBProbableDateTime.map(SDate(_).millisSinceEpoch),
    Actual = ga.ALDT.map(SDate(_).millisSinceEpoch),
    EstimatedChox = ga.EIBT.map(SDate(_).millisSinceEpoch),
    ActualChox = ga.AIBT.map(SDate(_).millisSinceEpoch),
    Gate = ga.GateCode,
    Stand = ga.StandCode,
    MaxPax = ga.MaxPax,
    ActPax = ga.TotalPassengerCount,
    TranPax = None,
    RunwayID = ga.Runway,
    BaggageReclaimId = ga.CarouselCode,
    AirportID = "GLA",
    Terminal = Terminal(ga.TerminalCode),
    rawIATA = ga.AirlineIATA + ga.FlightNumber,
    rawICAO = ga.AirlineICAO + ga.FlightNumber,
    Origin = ga.OriginDestAirportIATA,
    Scheduled = SDate(ga.ScheduledDateTime).millisSinceEpoch,
    PcpTime = None,
    FeedSources = Set(LiveFeedSource)
  )

  object JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {

    implicit val glaArrivalFormat: RootJsonFormat[GlaArrival] = jsonFormat22(GlaArrival)
  }

}
