package drt.server.feeds.ltn

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpEntity.Strict
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import drt.shared.Terminals.Terminal
import drt.shared.{Arrival, LiveFeedSource}
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

trait LtnFeedRequestLike {
  def getResponse: () => Future[HttpResponse]
}

case class LtnFeedRequester(endPoint: String, token: String, username: String, password: String)(implicit system: ActorSystem) extends LtnFeedRequestLike {
  val request = HttpRequest(
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

  def tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source
    .tick(0 millis, 30 seconds, NotUsed)
    .mapAsync(1) { _ =>
      log.info(s"Requesting feed")
      requestFeed()
    }

  def requestFeed(): Future[ArrivalsFeedResponse] = feedRequester.getResponse()
    .map {
      case HttpResponse(StatusCodes.OK, _, entity, _) =>
        responseToFeedResponse(entity)

      case HttpResponse(status, _, _, _) =>
        log.error(s"Got status $status")
        Future(ArrivalsFeedFailure(status.defaultMessage()))
    }
    .recover {
      case throwable => log.error("Caught error while retrieving the LTN port feed.", throwable)
        Future(ArrivalsFeedFailure(throwable.toString))
    }
    .flatten

  private def responseToFeedResponse(entity: ResponseEntity): Future[ArrivalsFeedResponse] = {
    import LtnLiveFlightProtocol._
    import spray.json._

    entity.toStrict(10 seconds).map {
      case Strict(_, data) =>
        Try(data.utf8String.parseJson.convertTo[Seq[LtnLiveFlight]].filter(_.DepartureArrivalType == Option("A"))) match {
          case Success(flights) => ArrivalsFeedSuccess(Flights(flights.map(toArrival)))
          case Failure(t) =>
            log.warn(s"Failed to parse ltn arrivals response", t)
            ArrivalsFeedFailure(t.getMessage)
        }
    }
  }

  def toArrival(ltnFeedFlight: LtnLiveFlight): Arrival = Arrival(
    Operator = ltnFeedFlight.AirlineDesc,
    Status = ltnFeedFlight.FlightStatusDesc.getOrElse("Scheduled"),
    Estimated = ltnFeedFlight.EstimatedDateTime.map(sdateWithTimeZoneApplied),
    Actual = ltnFeedFlight.ActualDateTime.map(sdateWithTimeZoneApplied),
    EstimatedChox = None,
    ActualChox = ltnFeedFlight.AIBT.map(sdateWithTimeZoneApplied),
    Gate = ltnFeedFlight.GateCode,
    Stand = ltnFeedFlight.StandCode,
    MaxPax = ltnFeedFlight.MaxPax,
    ActPax = ltnFeedFlight.TotalPassengerCount,
    TranPax = None,
    RunwayID = ltnFeedFlight.Runway,
    BaggageReclaimId = ltnFeedFlight.BaggageClaimUnit,
    AirportID = "LTN",
    Terminal = Terminal(ltnFeedFlight.TerminalCode.getOrElse(throw new Exception("Missing terminal"))),
    rawICAO = ltnFeedFlight.AirlineICAO.getOrElse(throw new Exception("Missing ICAO carrier code")) + ltnFeedFlight.FlightNumber.getOrElse(throw new Exception("Missing flight number")),
    rawIATA = ltnFeedFlight.AirlineIATA.getOrElse(throw new Exception("Missing IATA carrier code")) + ltnFeedFlight.FlightNumber.getOrElse(throw new Exception("Missing flight number")),
    Origin = ltnFeedFlight.OriginDestAirportIATA.getOrElse(throw new Exception("Missing origin IATA port code")),
    Scheduled = sdateWithTimeZoneApplied(ltnFeedFlight.ScheduledDateTime.getOrElse(throw new Exception("Missing scheduled date time"))),
    PcpTime = None,
    FeedSources = Set(LiveFeedSource)
  )

  def sdateWithTimeZoneApplied(dt: String): MillisSinceEpoch = {
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
                         ActualDateTime: Option[String],
                         OriginDestAirportIATA: Option[String]
                        )

object LtnLiveFlightProtocol extends DefaultJsonProtocol {
  implicit val ltnLiveFlightConverter: RootJsonFormat[LtnLiveFlight] = jsonFormat21(LtnLiveFlight)
}

