package drt.server.feeds.mag

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.server.feeds.Implicits._
import drt.server.feeds.mag.MagFeed.MagArrival
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, LiveFeedSource, PortCode, SDateLike, Terminals}
import org.slf4j.{Logger, LoggerFactory}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtHeader}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


trait FeedRequesterLike {
  def sendTokenRequest(header: String, claim: String, key: String, algorithm: JwtAlgorithm): String

  def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse]
}

object ProdFeedRequester extends FeedRequesterLike {
  override def sendTokenRequest(header: String, claim: String, key: String, algorithm: JwtAlgorithm): String = Jwt.encode(header: String, claim: String, key: String, algorithm: JwtAlgorithm)

  override def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse] = Http().singleRequest(request)
}

case class MagFeed(key: String, claimIss: String, claimRole: String, claimSub: String, now: () => SDateLike, portCode: PortCode, feedRequester: FeedRequesterLike)(implicit val system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def claim: String =
    s"""{
       |  "iss":"$claimIss",
       |  "role":"$claimRole",
       |  "sub":"$claimSub",
       |  "iat":${now().addMinutes(-1).millisSinceEpoch / 1000},
       |  "exp":${now().addMinutes(-1).addHours(1).millisSinceEpoch / 1000}
       |}
       |""".stripMargin

  val header: String = JwtHeader(JwtAlgorithm.RS256).toJson

  java.security.Security.addProvider(
    new org.bouncycastle.jce.provider.BouncyCastleProvider()
  )

  def newToken: String = feedRequester.sendTokenRequest(header = header, claim = claim, key = key, algorithm = JwtAlgorithm.RS256)

  def makeUri(start: SDateLike, end: SDateLike, from: Int, size: Int) = s"https://$claimSub/v1/flight/$portCode/arrival?startDate=${start.toISOString()}&endDate=${end.toISOString()}&from=$from&size=$size"

  def tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source
    .tick(initialDelay = 0 milliseconds, interval = 30 seconds, tick = NotUsed)
    .mapAsync(parallelism = 1)(_ => requestArrivals(now().addHours(hoursToAdd = -12)))

  def requestArrivals(start: SDateLike): Future[ArrivalsFeedResponse] =
    Source(0 to 1000 by 100)
      .mapAsync(parallelism = 10) { pageFrom =>
        requestArrivalsPage(start, pageFrom, size = 100)
      }
      .mapConcat {
        case Success(magArrivals) =>
          magArrivals.map(MagFeed.toArrival)
        case Failure(t) =>
          log.error(s"Failed to fetch or parse MAG arrivals: ${t.getMessage}")
          List()
      }
      .runWith(Sink.seq)
      .map {
        case as if as.nonEmpty =>
          val uniqueArrivals = as.map(a => (a.unique, a)).toMap.values.toSeq
          log.info(s"Sending ${uniqueArrivals.length} arrivals")
          ArrivalsFeedSuccess(Flights(uniqueArrivals), now())
        case as if as.isEmpty =>
          ArrivalsFeedFailure("No arrivals records received", now())
      }

  def requestArrivalsPage(start: SDateLike, from: Int, size: Int): Future[Try[List[MagArrival]]] = {
    val end = start.addHours(hoursToAdd = 24)
    val uri = makeUri(start, end, from, size)

    val token = newToken

    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = Uri(uri),
      headers = List(RawHeader("Authorization", s"Bearer $token")),
      entity = HttpEntity.Empty
    )

    val eventualArrivals = feedRequester
      .send(request)
      .map { response =>
        MagFeed
          .unmarshalResponse(response)
          .map { arrivals =>
            val relevantArrivals = arrivals.filter(isAppropriateArrival)
            log.info(s"${relevantArrivals.length} relevant arrivals out of ${arrivals.length} in results from offset $from")
            Success(relevantArrivals)
          }
          .recover { case t =>
            log.error("Error receiving MAG arrivals", t)
            Failure(t)
          }
      }
      .flatten

    eventualArrivals
  }

  private def isAppropriateArrival(a: MagArrival): Boolean = {
    a.arrival.terminal.isDefined && a.domesticInternational == "International" && a.flightNumber.trackNumber.isDefined
  }
}


object MagFeed {

  def unmarshalResponse(httpResponse: HttpResponse)(implicit materializer: Materializer): Future[List[MagArrival]] = {
    import JsonSupport._

    Unmarshal(httpResponse).to[List[MagArrival]]
  }

  case class MagArrivals(arrivals: Seq[MagArrival])

  case class MagArrival(uri: String,
                        operatingAirline: IataIcao,
                        flightNumber: FlightNumber,
                        departureAirport: IataIcao,
                        arrivalAirport: IataIcao,
                        arrivalDeparture: String,
                        domesticInternational: String,
                        flightType: String,
                        gate: Option[Gate],
                        stand: Option[Stand],
                        passenger: Passenger,
                        onBlockTime: Timings,
                        touchDownTime: Timings,
                        arrivalDate: String,
                        arrival: ArrivalDetails,
                        flightStatus: String)

  private def icao(magArrival: MagArrival) = f"${magArrival.operatingAirline.icao}${magArrival.flightNumber.trackNumber.map(_.toInt).getOrElse(0)}%04d"

  private def iata(magArrival: MagArrival) = f"${magArrival.operatingAirline.iata}${magArrival.flightNumber.trackNumber.map(_.toInt).getOrElse(0)}%04d"

  def toArrival(ma: MagArrival): Arrival = Arrival(
    Operator = Option(ma.operatingAirline.iata),
    Status = if (ma.onBlockTime.actual.isDefined) "On Chocks" else if (ma.touchDownTime.actual.isDefined) "Landed" else ma.flightStatus,
    Estimated = ma.arrival.estimated.map(str => SDate(str).millisSinceEpoch),
    Actual = ma.arrival.actual.map(str => SDate(str).millisSinceEpoch),
    EstimatedChox = ma.onBlockTime.estimated.map(str => SDate(str).millisSinceEpoch),
    ActualChox = ma.onBlockTime.actual.map(str => SDate(str).millisSinceEpoch),
    Gate = ma.gate.map(_.name.replace("Gate ", "")),
    Stand = ma.stand.flatMap(_.name.map(_.replace("Stand ", ""))),
    MaxPax = ma.passenger.maximum,
    ActPax = ma.passenger.count,
    TranPax = ma.passenger.transferCount,
    RunwayID = None,
    BaggageReclaimId = None,
    AirportID = ma.arrivalAirport.iata,
    Terminal = Terminals.Terminal(ma.arrival.terminal.getOrElse("")),
    rawICAO = icao(ma),
    rawIATA = iata(ma),
    Origin = ma.departureAirport.iata,
    Scheduled = SDate(ma.arrival.scheduled).millisSinceEpoch,
    PcpTime = None,
    FeedSources = Set(LiveFeedSource)
  )

  case class IataIcao(iata: String, icao: String)

  case class FlightNumber(airlineCode: String, trackNumber: Option[String])

  case class Gate(name: String, number: String)

  case class Stand(name: Option[String],
                   number: Option[String],
                   provisional: Boolean,
                   provisionalName: Option[String],
                   provisionalNumber: Option[String])

  case class BaggageClaim(name: String, number: String, firstBagReclaim: String, lastBagReclaim: String)

  case class Terminal(name: String, short_name: String, number: String)

  case class Passenger(count: Option[Int],
                       maximum: Option[Int],
                       transferCount: Option[Int],
                       prmCount: Option[Int])

  case class Timings(scheduled: String, estimated: Option[String], actual: Option[String])

  case class ArrivalDetails(airport: IataIcao,
                            scheduled: String,
                            estimated: Option[String],
                            actual: Option[String],
                            terminal: Option[String],
                            gate: Option[String])

  object JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
    implicit val passengerFormat: RootJsonFormat[Passenger] = jsonFormat4(Passenger)
    implicit val terminalFormat: RootJsonFormat[Terminal] = jsonFormat3(Terminal)
    implicit val baggageClaimFormat: RootJsonFormat[BaggageClaim] = jsonFormat4(BaggageClaim)
    implicit val standFormat: RootJsonFormat[Stand] = jsonFormat5(Stand)
    implicit val gateFormat: RootJsonFormat[Gate] = jsonFormat2(Gate)
    implicit val flightNumberFormat: RootJsonFormat[FlightNumber] = jsonFormat2(FlightNumber)
    implicit val timingsFormat: RootJsonFormat[Timings] = jsonFormat3(Timings)
    implicit val iataIcaoFormat: RootJsonFormat[IataIcao] = jsonFormat2(IataIcao)
    implicit val arrivalDetailsFormat: RootJsonFormat[ArrivalDetails] = jsonFormat6(ArrivalDetails)
    implicit val magArrivalFormat: RootJsonFormat[MagArrival] = jsonFormat16(MagArrival)
  }

}
