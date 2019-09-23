package feeds.mag

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.typesafe.config.{Config, ConfigFactory}
import drt.shared.{Arrival, LiveFeedSource, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import org.specs2.mutable.SpecificationLike
import pdi.jwt._
import services.SDate
import services.graphstages.Crunch
import spray.json.{DefaultJsonProtocol, RootJsonFormat}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Failure

class MagFeedSpec extends SpecificationLike {
  val config: Config = ConfigFactory.load()

  val keyPriv: String = config.getString("feeds.mag.private-key")
  val claimIss: String = config.getString("feeds.mag.claim.iss")
  val claimRole: String = config.getString("feeds.mag.claim.role")
  val claimSub: String = config.getString("feeds.mag.claim.sub")


  case class MagFeed(key: String, claimIss: String, claimRole: String, claimSub: String, now: () => SDateLike)(implicit val system: ActorSystem) {
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

    def newToken: String = Jwt.encode(header = header, claim = claim, key = keyPriv, algorithm = JwtAlgorithm.RS256)

    def makeUri(start: SDateLike, end: SDateLike, from: Int, size: Int) = s"https://$claimSub/v1/flight/man/arrival?startDate=${start.toISOString()}&endDate=${end.toISOString()}&from=$from&size=$size"

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
      import JsonSupport._

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
        .map {
          Unmarshal(_).to[List[MagArrival]]
        }
        .flatten
        .map {
          _.filter(a => a.terminal.isDefined && /*println(s"domInt: ${a.domesticInternational}")*/ a.domesticInternational == "International")
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

  implicit val system: ActorSystem = ActorSystem("mag-test")
  implicit val materialiser: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  val feed = MagFeed(keyPriv, claimIss, claimRole, claimSub, () => SDate.now())

  "Given a jwt client " +
    "I can generate an encoded token" >> {
    skipped("exploratory test")

    val token = feed.newToken

    token.nonEmpty
  }

  "Given a json response containing a single flight " +
    "I should be able to convert it to a representative case class" >> {
    import JsonSupport._

    val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, jsonResponseSingleArrival))

    Await.result(Unmarshal(response).to[List[MagArrival]], 1 second) match {
      case _ => println(s"Got some mag arrivals")
    }

    success
  }

  "Given a jwt token " +
    "When I request arrivals " >> {
    val start = SDate(Crunch.getLocalLastMidnight(SDate.now()).millisSinceEpoch)

    val arrivals = Await.result(feed.requestArrivals(start), 30 seconds) match {
      case MagArrivals(magArrivals) if magArrivals.nonEmpty =>
        println(s"Got some MAG arrivals")
        magArrivals
      case _ =>
        println(s"Got no MAG arrivals :(")
        Seq()
    }

    val parsedMagArrivalCount = arrivals.length

    println(s"Parsed $parsedMagArrivalCount mag arrivals")

    "I should get a none-zero length list of MagArrivals" >> {
      parsedMagArrivalCount >= 10
    }

    val drtArrivals = arrivals.map(MagArrivalConversion.toArrival)

    drtArrivals.groupBy(_.Terminal).foreach {
      case (terminal, as) => println(s"Terminal $terminal (${as.length} arrivals):\n${as.sortBy(_.Scheduled).map(a => s"${a.IATA} ${SDate(a.Scheduled).toISOString()}, ${a.Estimated.map(SDate(_).toISOString())}-> ${a.ActPax}").mkString("\n")}")
    }

    "I should get the same number of DRT Arrivals" >> {
      drtArrivals.length === parsedMagArrivalCount
    }

  }

  val jsonResponseSingleArrival: String =
    """[
      |    {
      |        "uniqueRef": 16961558,
      |        "uri": "https://api.prod.bi.magairports.com/v1/flight/MAN/arrival/LS/073W/2019-09-23/NCL",
      |        "magAirport": "MAN",
      |        "operatingAirline": {
      |            "iata": "LS",
      |            "icao": "LS"
      |        },
      |        "aircraftType": {
      |            "iata": "75W"
      |        },
      |        "flightNumber": {
      |            "airlineCode": "LS",
      |            "trackNumber": "073",
      |            "suffix": "W"
      |        },
      |        "departureAirport": {
      |            "iata": "NCL",
      |            "icao": "EGNT"
      |        },
      |        "arrivalAirport": {
      |            "iata": "MAN",
      |            "icao": "EGCC"
      |        },
      |        "arrivalDeparture": "Arrival",
      |        "domesticInternational": "Domestic",
      |        "flightType": "Positioning",
      |        "stand": {
      |            "provisional": true,
      |            "provisionalName": "Stand 233",
      |            "provisionalNumber": "233"
      |        },
      |        "terminal": {
      |            "name": "Terminal 1",
      |            "short_name": "T1",
      |            "number": "1"
      |        },
      |        "handlingAgent": "WFSUK",
      |        "passenger": {
      |            "count": 0,
      |            "maximum": 235,
      |            "prmCount": 0
      |        },
      |        "offBlockTime": {
      |            "scheduled": "2019-09-23T09:50:00+00:00"
      |        },
      |        "onBlockTime": {
      |            "scheduled": "2019-09-23T09:50:00+00:00"
      |        },
      |        "touchDownTime": {
      |            "scheduled": "2019-09-23T09:50:00+00:00"
      |        },
      |        "arrivalDate": "2019-09-23",
      |        "arrival": {
      |            "airport": {
      |                "iata": "MAN",
      |                "icao": "EGCC"
      |            },
      |            "scheduled": "2019-09-23T09:50:00+00:00",
      |            "terminal": "T1"
      |        },
      |        "flightStatus": "Cancelled"
      |    }
      |]""".stripMargin

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

  object MagArrivalConversion {
    private def icao(magArrival: MagArrival) = f"${magArrival.operatingAirline.icao}${magArrival.flightNumber.trackNumber.toInt}%04d"

    private def iata(magArrival: MagArrival) = f"${magArrival.operatingAirline.iata}${magArrival.flightNumber.trackNumber.toInt}%04d"

    def toArrival(ma: MagArrival): Arrival = Arrival(
      Operator = Option(ma.operatingAirline.iata),
      Status = ma.flightStatus,
      Estimated = ma.arrival.estimated.map(str => SDate(str).millisSinceEpoch),
      Actual = ma.arrival.actual.map(str => SDate(str).millisSinceEpoch),
      EstimatedChox = ma.onBlockTime.estimated.map(str => SDate(str).millisSinceEpoch),
      ActualChox = ma.onBlockTime.actual.map(str => SDate(str).millisSinceEpoch),
      Gate = ma.gate.map(_.name),
      Stand = ma.stand.flatMap(_.name),
      MaxPax = Option(ma.passenger.maximum),
      ActPax = ma.passenger.count,
      TranPax = ma.passenger.transferCount,
      RunwayID = None,
      BaggageReclaimId = None,
      FlightID = None,
      AirportID = ma.arrivalAirport.icao,
      Terminal = ma.terminal.map(_.name).getOrElse(""),
      rawICAO = icao(ma),
      rawIATA = iata(ma),
      Origin = ma.departureAirport.icao,
      Scheduled = SDate(ma.arrival.scheduled).millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set(LiveFeedSource)
    )
  }

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
