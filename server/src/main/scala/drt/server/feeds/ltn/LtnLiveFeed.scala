package drt.server.feeds.ltn

import akka.actor.{ActorSystem, typed}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, LiveArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

trait LtnFeedRequestLike {
  def getResponse: () => Future[HttpResponse]
}

case class LtnFeedRequester(endPoint: String, token: String, username: String, password: String)
                           (implicit system: ActorSystem) extends LtnFeedRequestLike {
  println(s"Creating LtnFeedRequester with endPoint: $endPoint, token: ${token.takeRight(30)}, username: $username, password: ${password.take(4)}..${password.takeRight(4)}")
  val request: HttpRequest = HttpRequest(
    method = HttpMethods.GET,
    uri = Uri(endPoint),
    headers = List(
      RawHeader("token", token),
      RawHeader("username", username),
      RawHeader("password", password)
    ),
    entity = HttpEntity.Empty
  )

  val getResponse: () => Future[HttpResponse] = () => Http().singleRequest(request)
}

case class LtnLiveFeed(feedRequester: LtnFeedRequestLike, timeZone: DateTimeZone)(implicit materializer: Materializer) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def source(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] =
    source.mapAsync(1) { _ =>
      log.info(s"Requesting feed")
      requestFeed()
    }

  def requestFeed(): Future[ArrivalsFeedResponse] = feedRequester.getResponse()
    .flatMap {
      case HttpResponse(StatusCodes.OK, _, entity, _) =>
        responseToFeedResponse(entity)

      case HttpResponse(status, _, entity, _) =>
        entity.dataBytes.runFold("")((acc, b) => acc + b.utf8String).map { body =>
          log.error(s"Got status $status with body $body")
          ArrivalsFeedFailure(status.defaultMessage())
        }
    }
    .recoverWith {
      case throwable =>
        log.error("Caught error while retrieving the LTN port feed.", throwable)
        Future.successful(ArrivalsFeedFailure(throwable.toString))
    }

  private def responseToFeedResponse(entity: ResponseEntity): Future[ArrivalsFeedResponse] = {
    import LtnLiveFlightProtocol._
    import spray.json._

    entity.toStrict(10.seconds).map {
      case Strict(_, data) =>
        Try(data.utf8String.parseJson.convertTo[Seq[LtnLiveFlight]].filter(_.DepartureArrivalType == Option("A"))) match {
          case Success(flights) => ArrivalsFeedSuccess(flights.map(toArrival))
          case Failure(t) =>
            log.warn(s"Failed to parse ltn arrivals response", t)
            ArrivalsFeedFailure(t.getMessage)
        }
    }
  }

  def toArrival(ltnFeedFlight: LtnLiveFlight): LiveArrival = {
    val operator: String = ltnFeedFlight.AirlineDesc.getOrElse("")
    val status: String = ltnFeedFlight.FlightStatusDesc.getOrElse("Scheduled")
    val carrier = ltnFeedFlight.AirlineIATA.getOrElse(throw new Exception("Missing carrier code"))
    val flightNumber = ltnFeedFlight.FlightNumber.getOrElse(throw new Exception("Missing flight number"))
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(carrier + flightNumber)

    LiveArrival(
      operator = Option(operator),
      maxPax = ltnFeedFlight.MaxPax,
      totalPax = ltnFeedFlight.TotalPassengerCount,
      transPax = None,
      terminal = Terminal(ltnFeedFlight.TerminalCode.getOrElse(throw new Exception("Missing terminal"))),
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = ltnFeedFlight.OriginDestAirportIATA.getOrElse(throw new Exception("Missing origin IATA port code")),
      scheduled = sdateWithTimeZoneApplied(ltnFeedFlight.ScheduledDateTime.getOrElse(throw new Exception("Missing scheduled date time"))),
      estimated = ltnFeedFlight.EstimatedDateTime.map(sdateWithTimeZoneApplied),
      touchdown = ltnFeedFlight.ALDT.map(sdateWithTimeZoneApplied),
      estimatedChox = None,
      actualChox = ltnFeedFlight.AIBT.map(sdateWithTimeZoneApplied),
      status = status,
      gate = ltnFeedFlight.GateCode,
      stand = ltnFeedFlight.StandCode,
      runway = ltnFeedFlight.Runway,
      baggageReclaim = ltnFeedFlight.BaggageClaimUnit,
    )
  }

  private def sdateWithTimeZoneApplied(dt: String): MillisSinceEpoch = {
    val rawDate = SDate(dt)
    val offsetMillis = timeZone.getOffset(rawDate.millisSinceEpoch)
    rawDate.millisSinceEpoch - offsetMillis
  }
}

case class LtnLiveFlight(TotalPassengerCount: Option[Int],
                         AirlineIATA: Option[String],
                         FlightStatus: Option[String],
                         FlightNumber: Option[String],
                         OriginDestAirportICAO: Option[String],
                         TerminalCode: Option[String],
                         DepartureArrivalType: Option[String],
                         ScheduledDateTime: Option[String],
                         Runway: Option[String],
                         StandCode: Option[String],
                         PaxTransfer: Option[Int],
                         BaggageClaimUnit: Option[String],
                         EstimatedDateTime: Option[String],
                         GateCode: Option[String],
                         AirlineICAO: Option[String],
                         FlightStatusDesc: Option[String],
                         AirlineDesc: Option[String],
                         MaxPax: Option[Int],
                         AIBT: Option[String],
                         ALDT: Option[String],
                         OriginDestAirportIATA: Option[String]
                        )

object LtnLiveFlightProtocol extends DefaultJsonProtocol {
  implicit val ltnLiveFlightConverter: RootJsonFormat[LtnLiveFlight] = jsonFormat21(LtnLiveFlight)
}
