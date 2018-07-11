package drt.server.feeds.ltn

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import drt.shared.Arrival
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.Flights
import org.joda.time.DateTimeZone
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, ArrivalsFeedResponse}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class LtnLiveFeed(endPoint: String, token: String, username: String, password: String, timeZone: DateTimeZone) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source
    .tick(0 millis, 30 seconds, NotUsed)
    .map(_ => {
      log.info(s"Requesting feed")
      requestFeed()
    })

  def requestFeed(): ArrivalsFeedResponse = {
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

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    import scala.concurrent.ExecutionContext.Implicits.global

    val responseFuture = Http()
      .singleRequest(request)
      .map {
        case HttpResponse(StatusCodes.OK, _, entity, _) =>
          import LtnLiveFlightProtocol._
          import spray.json._

          Try(Await.result(entity.toStrict(10 seconds), 10 seconds).data.utf8String.parseJson.convertTo[Seq[LtnLiveFlight]]) match {
            case Success(flights) =>
              val arrivals = flights.filter(_.DepartureArrivalType == Option("A"))
              log.info(s"parsed ${arrivals.length} arrivals from ${flights.length} flights")
              ArrivalsFeedSuccess(Flights(arrivals.map(toArrival)))
            case Failure(t) =>
              log.info(s"Failed to parse: $t")
              ArrivalsFeedFailure(t.toString)
          }
        case HttpResponse(status, _, _, _) => ArrivalsFeedFailure(status.defaultMessage())
      }
      .recoverWith {
        case throwable => Future(ArrivalsFeedFailure(throwable.toString))
      }

    Await.result(responseFuture, 5 seconds)
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
    FlightID = None,
    AirportID = "LTN",
    Terminal = ltnFeedFlight.TerminalCode.getOrElse(throw new Exception("Missing terminal")),
    rawICAO = ltnFeedFlight.AirlineICAO.getOrElse(throw new Exception("Missing ICAO carrier code")) + ltnFeedFlight.FlightNumber.getOrElse(throw new Exception("Missing flight number")),
    rawIATA = ltnFeedFlight.AirlineIATA.getOrElse(throw new Exception("Missing IATA carrier code")) + ltnFeedFlight.FlightNumber.getOrElse(throw new Exception("Missing flight number")),
    Origin = ltnFeedFlight.OriginDestAirportIATA.getOrElse(throw new Exception("Missing origin IATA port code")),
    Scheduled = sdateWithTimeZoneApplied(ltnFeedFlight.ScheduledDateTime.getOrElse(throw new Exception("Missing scheduled date time"))),
    PcpTime = None,
    LastKnownPax = None
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

