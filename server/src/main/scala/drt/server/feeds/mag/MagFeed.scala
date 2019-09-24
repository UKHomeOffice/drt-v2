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
import drt.server.feeds.mag.MagFeed.{MagArrival, MagArrivals}
import drt.shared.FlightsApi.Flights
import drt.shared.{Arrival, LiveFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtHeader}
import server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.Failure

case class MagFeed(key: String, claimIss: String, claimRole: String, claimSub: String, now: () => SDateLike, portCode: String)(implicit val system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) {
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

  def newToken: String = Jwt.encode(header = header, claim = claim, key = key, algorithm = JwtAlgorithm.RS256)

  def makeUri(start: SDateLike, end: SDateLike, from: Int, size: Int) = s"https://$claimSub/v1/flight/$portCode/arrival?startDate=${start.toISOString()}&endDate=${end.toISOString()}&from=$from&size=$size"

  def tickingSource: Source[ArrivalsFeedResponse, Cancellable] = Source
    .tick(0 milliseconds, 30 seconds, NotUsed)
    .mapAsync(1)(_ => requestArrivals(now().addHours(-12)))
    .map(_.arrivals.map(MagFeed.toArrival))
    .map(as => ArrivalsFeedSuccess(Flights(as), now()))

  def requestArrivals(start: SDateLike): Future[MagArrivals] =
    Source(0 to 500 by 100)
      .mapAsync(10) { pageFrom =>
        val end = start.addHours(36)
        requestArrivalsPage(start, end, pageFrom, 100)
      }
      .mapConcat(identity)
      .runWith(Sink.seq)
      .map(as => MagArrivals(as))

  def requestArrivalsPage(start: SDateLike, end: SDateLike, from: Int, size: Int): Future[List[MagArrival]] = {

    val end = start.addHours(24)
    val uri = makeUri(start, end, from, size)

    val token = newToken

    val request = HttpRequest(
      method = HttpMethods.GET,
      uri = Uri(uri),
      headers = List(RawHeader("Authorization", s"Bearer $token")),
      entity = HttpEntity.Empty
    )

    val eventualArrivals = Http()
      .singleRequest(request)
      .map(MagFeed.unmarshalResponse)
      .flatten
      .map {
        _.filter(a => a.arrival.terminal.isDefined && a.domesticInternational == "International")
      }

    eventualArrivals.onComplete {
      case Failure(t) =>
        log.error(s"Failed to fetch or parse MAG arrivals: $t")
      case _ =>
    }

    eventualArrivals.recoverWith {
      case t =>
        log.error(s"Failed to fetch or parse MAG arrivals: ${t.getMessage}")
        Future(None)
    }

    eventualArrivals
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
                        terminal: Option[Terminal],
                        passenger: Passenger,
                        onBlockTime: Timings,
                        touchDownTime: Timings,
                        arrivalDate: String,
                        arrival: ArrivalDetails,
                        flightStatus: String)

  private def icao(magArrival: MagArrival) = f"${magArrival.operatingAirline.icao}${magArrival.flightNumber.trackNumber.toInt}%04d"

  private def iata(magArrival: MagArrival) = f"${magArrival.operatingAirline.iata}${magArrival.flightNumber.trackNumber.toInt}%04d"

  def toArrival(ma: MagArrival): Arrival = Arrival(
    Operator = Option(ma.operatingAirline.iata),
    Status = if (ma.onBlockTime.actual.isDefined) "On Chocks" else if (ma.touchDownTime.actual.isDefined) "Landed" else ma.flightStatus,
    Estimated = ma.arrival.estimated.map(str => SDate(str).millisSinceEpoch),
    Actual = ma.arrival.actual.map(str => SDate(str).millisSinceEpoch),
    EstimatedChox = ma.onBlockTime.estimated.map(str => SDate(str).millisSinceEpoch),
    ActualChox = ma.onBlockTime.actual.map(str => SDate(str).millisSinceEpoch),
    Gate = ma.gate.map(_.name.replace("Gate ", "")),
    Stand = ma.stand.flatMap(_.name.map(_.replace("Stand ", ""))),
    MaxPax = Option(ma.passenger.maximum),
    ActPax = ma.passenger.count,
    TranPax = ma.passenger.transferCount,
    RunwayID = None,
    BaggageReclaimId = None,
    FlightID = None,
    AirportID = ma.arrivalAirport.iata,
    Terminal = ma.arrival.terminal.getOrElse(""),
    rawICAO = icao(ma),
    rawIATA = iata(ma),
    Origin = ma.departureAirport.iata,
    Scheduled = SDate(ma.arrival.scheduled).millisSinceEpoch,
    PcpTime = None,
    FeedSources = Set(LiveFeedSource)
  )

  case class IataIcao(iata: String, icao: String)

  case class FlightNumber(airlineCode: String, trackNumber: String)

  case class Gate(name: String, number: String)

  case class Stand(name: Option[String],
                   number: Option[String],
                   provisional: Boolean,
                   provisionalName: Option[String],
                   provisionalNumber: Option[String])

  case class BaggageClaim(name: String, number: String, firstBagReclaim: String, lastBagReclaim: String)

  case class Terminal(name: String, short_name: String, number: String)

  case class Passenger(count: Option[Int],
                       maximum: Int,
                       transferCount: Option[Int],
                       prmCount: Int)

  case class Timings(scheduled: String, estimated: Option[String], actual: Option[String])

  case class ArrivalDetails(airport: IataIcao,
                            scheduled: String,
                            estimated: Option[String],
                            actual: Option[String],
                            terminal: Option[String],
                            gate: Option[String])

  object JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
    implicit val PassengerFormat: RootJsonFormat[Passenger] = jsonFormat4(Passenger)
    implicit val TerminalFormat: RootJsonFormat[Terminal] = jsonFormat3(Terminal)
    implicit val BaggageClaimFormat: RootJsonFormat[BaggageClaim] = jsonFormat4(BaggageClaim)
    implicit val StandFormat: RootJsonFormat[Stand] = jsonFormat5(Stand)
    implicit val GateFormat: RootJsonFormat[Gate] = jsonFormat2(Gate)
    implicit val FlightNumberFormat: RootJsonFormat[FlightNumber] = jsonFormat2(FlightNumber)
    implicit val TimingsFormat: RootJsonFormat[Timings] = jsonFormat3(Timings)
    implicit val iataIcaoFormat: RootJsonFormat[IataIcao] = jsonFormat2(IataIcao)
    implicit val ArrivalDetailsFormat: RootJsonFormat[ArrivalDetails] = jsonFormat6(ArrivalDetails)
    implicit val magArrivalFormat: RootJsonFormat[MagArrival] = jsonFormat17(MagArrival)
  }

}
