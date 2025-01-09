package drt.server.feeds.mag

import akka.actor.{ActorSystem, typed}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.server.feeds.Feed.FeedTick
import drt.server.feeds.mag.MagFeed.MagArrival
import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import org.slf4j.{Logger, LoggerFactory}
import pdi.jwt.{Jwt, JwtAlgorithm, JwtHeader}
import spray.json.{DefaultJsonProtocol, RootJsonFormat}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, LiveArrival}
import uk.gov.homeoffice.drt.ports.{PortCode, Terminals}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}


trait FeedRequesterLike {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def createToken(header: String, claim: String, key: String, algorithm: JwtAlgorithm): String

  def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse]
}

object ProdFeedRequester extends FeedRequesterLike {
  override def createToken(header: String, claim: String, key: String, algorithm: JwtAlgorithm): String =
    Try(Jwt.encode(header: String, claim: String, key: String, algorithm: JwtAlgorithm)).getOrElse("")

  override def send(request: HttpRequest)
                   (implicit actorSystem: ActorSystem): Future[HttpResponse] =
    Http().singleRequest(request)
}

case class MagFeed(key: String,
                   claimIss: String,
                   claimRole: String,
                   claimSub: String,
                   now: () => SDateLike,
                   portCode: PortCode,
                   feedRequester: FeedRequesterLike)
                  (implicit val system: ActorSystem, materializer: Materializer, executionContext: ExecutionContext) {
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

  def newToken: String = feedRequester.createToken(header = header, claim = claim, key = key, algorithm = JwtAlgorithm.RS256)

  private def makeUri(start: SDateLike, end: SDateLike, from: Int, size: Int): String =
    s"https://$claimSub/v1/flight/$portCode/arrival?startDate=${start.toISOString}&endDate=${end.toISOString}&from=$from&size=$size"

  def source(source: Source[FeedTick, typed.ActorRef[FeedTick]]): Source[ArrivalsFeedResponse, typed.ActorRef[FeedTick]] =
    source.mapAsync(parallelism = 1)(_ => requestArrivals(now().addHours(hoursToAdd = -12)))

  def requestArrivals(start: SDateLike): Future[ArrivalsFeedResponse] =
    Source(0 to 1000 by 100)
      .mapAsync(parallelism = 10) { pageFrom =>
        requestArrivalsPage(start, pageFrom, size = 100)
          .recover { case t =>
            log.error("Failed to fetch arrivals page", t)
            Failure(t)
          }
      }
      .mapConcat {
        case Success(magArrivals) =>
          magArrivals.map(MagFeed.toArrival)
        case Failure(t) =>
          log.error(s"Failed to fetch or parse MAG arrivals: ${t.getMessage}")
          List()
      }
      .log(getClass.getName)
      .runWith(Sink.seq)
      .map {
        case as if as.nonEmpty =>
          val uniqueArrivals = as.map(a => (a.unique, a)).toMap.values.toSeq
          log.info(s"Sending ${uniqueArrivals.length} arrivals")
          ArrivalsFeedSuccess(uniqueArrivals, now())
        case as if as.isEmpty =>
          ArrivalsFeedFailure("No arrivals records received", now())
      }

  private def requestArrivalsPage(start: SDateLike, from: Int, size: Int): Future[Try[List[MagArrival]]] = {
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
  private def unmarshalResponse(httpResponse: HttpResponse)(implicit materializer: Materializer): Future[List[MagArrival]] = {
    import JsonSupport._

    Unmarshal(httpResponse).to[List[MagArrival]]
  }

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
  def toArrival(ma: MagArrival): LiveArrival = {
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(ma.operatingAirline.iata + ma.flightNumber.trackNumber.getOrElse(""))

    LiveArrival(
      operator = Option(ma.operatingAirline.iata),
      maxPax = ma.passenger.maximum,
      totalPax = ma.passenger.count,
      transPax = ma.passenger.transferCount,
      terminal = Terminals.Terminal(ma.arrival.terminal.getOrElse("")),
      voyageNumber = voyageNumber.numeric,
      carrierCode = carrierCode.code,
      flightCodeSuffix = suffix.map(_.suffix),
      origin = ma.departureAirport.iata,
      previousPort = None,
      scheduled = SDate(ma.arrival.scheduled).millisSinceEpoch,
      estimated = ma.arrival.estimated.map(str => SDate(str).millisSinceEpoch),
      touchdown = ma.arrival.actual.map(str => SDate(str).millisSinceEpoch),
      estimatedChox = ma.onBlockTime.estimated.map(str => SDate(str).millisSinceEpoch),
      actualChox = ma.onBlockTime.actual.map(str => SDate(str).millisSinceEpoch),
      status = if (ma.onBlockTime.actual.isDefined) "On Chocks" else if (ma.touchDownTime.actual.isDefined) "Landed" else ma.flightStatus,
      gate = ma.gate.map(_.name.replace("Gate ", "")),
      stand = ma.stand.flatMap(_.name.map(_.replace("Stand ", ""))),
      runway = None,
      baggageReclaim = None,
    )
  }

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
