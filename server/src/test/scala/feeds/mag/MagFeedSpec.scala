package feeds.mag

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import com.typesafe.config.{Config, ConfigFactory}
import drt.server.feeds.mag.MagFeed
import drt.server.feeds.mag.MagFeed.{MagArrival, MagArrivals}
import org.specs2.mutable.SpecificationLike
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}

class MagFeedSpec extends SpecificationLike {
  val config: Config = ConfigFactory.load()

  val keyPriv: String = config.getString("feeds.mag.private-key")
  val claimIss: String = config.getString("feeds.mag.claim.iss")
  val claimRole: String = config.getString("feeds.mag.claim.role")
  val claimSub: String = config.getString("feeds.mag.claim.sub")


  implicit val system: ActorSystem = ActorSystem("mag-test")
  implicit val materialiser: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  val feed = MagFeed(keyPriv, claimIss, claimRole, claimSub, () => SDate.now(), "MAN")

  "Given a jwt client " +
    "I can generate an encoded token" >> {
    skipped("exploratory test")

    val token = feed.newToken

    token.nonEmpty
  }

  "Given a json response containing a single flight " +
    "I should be able to convert it to a representative case class" >> {
    skipped("exploratory test")
    import MagFeed.JsonSupport._

    val response = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, jsonResponseSingleArrival))

    Await.result(Unmarshal(response).to[List[MagArrival]], 1 second) match {
      case _ => println(s"Got some mag arrivals")
    }

    success
  }

  "Given a jwt token " +
    "When I request arrivals " >> {
    skipped("exploratory test")
    val start = SDate(Crunch.getLocalLastMidnight(SDate.now()).millisSinceEpoch)

    val arrivals = Await.result(feed.requestArrivals(start), 30 seconds) match {
      case MagArrivals(magArrivals) if magArrivals.nonEmpty =>
        magArrivals
      case _ =>
        Seq()
    }

    val parsedMagArrivalCount = arrivals.length

    val nonZeroMagArrivals = parsedMagArrivalCount >= 10

    val drtArrivals = arrivals.map(MagFeed.toArrival)

    nonZeroMagArrivals === true && drtArrivals.length === parsedMagArrivalCount
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

}
