package drt.server.feeds

import akka.actor.typed.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival}

import scala.concurrent.{ExecutionContext, Future}

object AzinqFeed extends SprayJsonSupport with DefaultJsonProtocol {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def source(source: Source[FeedTick, ActorRef[FeedTick]],
             fetchArrivals: () => Future[Seq[FeedArrival]],
            )
            (implicit ec: ExecutionContext): Source[ArrivalsFeedResponse, ActorRef[FeedTick]] =
    source.mapAsync(1)(_ => {
      log.info(s"Requesting live feed.")
      fetchArrivals()
        .map(arrivals => ArrivalsFeedSuccess(arrivals))
        .recover {
          case t =>
            log.error("Failed to fetch arrivals", t)
            ArrivalsFeedFailure("Failed to fetch arrivals")
        }
    })

  def apply[A <: Arriveable](uri: String,
                             username: String,
                             password: String,
                             token: String,
                             httpRequest: HttpRequest => Future[HttpResponse],
                            )
                            (implicit ec: ExecutionContext, mat: Materializer, json: RootJsonFormat[A]): () => Future[Seq[FeedArrival]] = {
    val request = HttpRequest(
      uri = uri,
      headers = List(
        RawHeader("token", token),
        RawHeader("username", username),
        RawHeader("password", password),
      ))

    () =>
      httpRequest(request)
        .flatMap(Unmarshal[HttpResponse](_).to[List[A]])
        .map(_.filter(_.isValid).map(_.toArrival))
  }
}

trait Arriveable {
  def toArrival: FeedArrival

  def isValid: Boolean
}
