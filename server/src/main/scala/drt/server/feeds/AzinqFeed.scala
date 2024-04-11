package drt.server.feeds

import akka.actor.typed.ActorRef
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpRequest, HttpResponse}
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{FeedArrival, FlightCode, LiveArrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

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
  val AirlineIATA: String
  val FlightNumber: String
  val MaxPax: Option[Int]
  val TotalPassengerCount: Option[Int]
  val OriginDestAirportIATA: String
  val ALDT: Option[String]
  val AIBT: Option[String]
  val FlightStatus: String
  val GateCode: Option[String]
  val StandCode: Option[String]
  val CarouselCode: Option[String]

  val terminal: Terminal
  val ScheduledDateTime: String
  val maybeEstimated: Option[Long]
  val maybeEstimatedChox: Option[Long]
  val runway: Option[String]

  lazy val (carrierCode, voyageNumberLike, maybeSuffix) = FlightCode.flightCodeToParts(AirlineIATA + FlightNumber)
  lazy val voyageNumber: VoyageNumber = voyageNumberLike match {
    case vn: VoyageNumber => vn
    case _ => throw new Exception(s"Failed to parse voyage number from ${AirlineIATA + FlightNumber}")
  }

  def toArrival: FeedArrival = {
    LiveArrival(
      operator = None,
      maxPax = MaxPax,
      totalPax = TotalPassengerCount,
      transPax = None,
      terminal = terminal,
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = maybeSuffix.map(_.suffix),
      origin = OriginDestAirportIATA,
      scheduled = SDate(ScheduledDateTime).millisSinceEpoch,
      estimated = maybeEstimated,
      touchdown = ALDT.map(SDate(_).millisSinceEpoch),
      estimatedChox = maybeEstimatedChox,
      actualChox = AIBT.map(SDate(_).millisSinceEpoch),
      status = FlightStatus,
      gate = GateCode,
      stand = StandCode,
      runway = runway,
      baggageReclaim = CarouselCode,
    )
  }

  def isValid: Boolean
}
