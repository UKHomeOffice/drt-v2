package drt.server.feeds.ltn

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess, FeedResponse}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

case class LtnLiveFeed(endPoint: String, token: String, username: String, password: String) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def tickingSource: Source[FeedResponse, Cancellable] = Source
    .tick(0 millis, 30 seconds, NotUsed)
    .map(_ => {
      log.info(s"Requesting feed")
      requestFeed()
    })

  def requestFeed(): FeedResponse = {
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
              ArrivalsFeedSuccess(Flights(arrivals.map(_.toArrival)))
            case Failure(t) =>
              log.info(s"Failed to parse: $t")
              ArrivalsFeedFailure(t.toString)
          }
        case HttpResponse(status, _, _, _) => ArrivalsFeedFailure(status.defaultMessage())
      }

    Await.result(responseFuture, 5 seconds)
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
                        ) {
  def toArrival: Arrival = Arrival(
    Operator = AirlineDesc,
    Status = FlightStatusDesc.getOrElse("Scheduled"),
    Estimated = EstimatedDateTime.map(est => SDate.parseString(est).millisSinceEpoch),
    Actual = ActualDateTime.map(act => SDate.parseString(act).millisSinceEpoch),
    EstimatedChox = None,
    ActualChox = AIBT.map(act => SDate.parseString(act).millisSinceEpoch),
    Gate = GateCode,
    Stand = StandCode,
    MaxPax = MaxPax,
    ActPax = TotalPassengerCount,
    TranPax = None,
    RunwayID = Runway,
    BaggageReclaimId = BaggageClaimUnit,
    FlightID = None,
    AirportID = "LTN",
    Terminal = TerminalCode.getOrElse(throw new Exception("Missing terminal")),
    rawICAO = AirlineICAO.getOrElse(throw new Exception("Missing ICAO carrier code")) + FlightNumber.getOrElse(throw new Exception("Missing flight number")),
    rawIATA = AirlineIATA.getOrElse(throw new Exception("Missing IATA carrier code")) + FlightNumber.getOrElse(throw new Exception("Missing flight number")),
    Origin = OriginDestAirportIATA.getOrElse(throw new Exception("Missing origin IATA port code")),
    Scheduled = SDate.parseString(ScheduledDateTime.getOrElse(throw new Exception("Missing scheduled date time"))).millisSinceEpoch,
    PcpTime = None,
    LastKnownPax = None
  )

}

object LtnLiveFlightProtocol extends DefaultJsonProtocol {
  implicit val ltnLiveFlightConverter: RootJsonFormat[LtnLiveFlight] = jsonFormat21(LtnLiveFlight)
}

