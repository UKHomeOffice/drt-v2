package feeds.mag

import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import com.typesafe.config.{Config, ConfigFactory}
import drt.server.feeds.mag.{FeedRequesterLike, MagFeed, ProdFeedRequester}
import drt.shared.FlightsApi.Flights
import pdi.jwt.JwtAlgorithm
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}

object MockFeedRequester extends FeedRequesterLike {
  private val defaultResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, "[]"))
  var mockResponse: HttpResponse = defaultResponse

  override def sendTokenRequest(header: String,
                                claim: String,
                                key: String,
                                algorithm: JwtAlgorithm): String = "Fake token"

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse] = Future(mockResponse)
}

case class MockExceptionThrowingFeedRequester(causeException: () => Unit) extends FeedRequesterLike {
  override def sendTokenRequest(header: String,
                                claim: String,
                                key: String,
                                algorithm: JwtAlgorithm): String = "Fake token"

  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

  def send(request: HttpRequest)(implicit actorSystem: ActorSystem): Future[HttpResponse] = {
    causeException()
    Future(HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, "[]")))
  }
}

class MagFeedSpec extends CrunchTestLike {
  val config: Config = ConfigFactory.load()

  val privateKey: String = config.getString("feeds.mag.private-key")
  val claimIss: String = config.getString("feeds.mag.claim.iss")
  val claimRole: String = config.getString("feeds.mag.claim.role")
  val claimSub: String = config.getString("feeds.mag.claim.sub")

  val feed: MagFeed = MagFeed(privateKey, claimIss, claimRole, claimSub, () => SDate.now(), PortCode("MAN"), MockFeedRequester)

  "Given a jwt client " +
    "I can generate an encoded token" >> {
    skipped("exploratory test")

    val token = feed.newToken

    log.info(s"Token: $token")

    token.nonEmpty
  }

  "Given a mock json response containing a single valid flight " +
    "I should get a single flights " >> {
    MockFeedRequester.mockResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, jsonResponseSingleArrival))

    val result = Await.result(feed.requestArrivals(SDate.now()), 1 second) match {
      case ArrivalsFeedSuccess(Flights(arrivals), _) => arrivals
      case _ => List()
    }

    result.size === 1
  }

  "Given a mock json response containing a single valid flight with 0 for passenger count and max pax " +
    "I should see those values in the arrival " >> {
    MockFeedRequester.mockResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, jsonResponseSingleArrivalWith0Pax))

    val actMax = Await.result(feed.requestArrivals(SDate.now()), 1 second) match {
      case ArrivalsFeedSuccess(Flights(arrivals),_) => (arrivals.head.ActPax, arrivals.head.MaxPax)
      case _ => List()
    }

    val expected = (Some(0), Some(0))

    actMax === expected

  }

  "Given a mock json response containing invalid json " +
    "I should get an ArrivalsFeedFailure" >> {
    MockFeedRequester.mockResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, "bad json"))

    val result = Await.result(feed.requestArrivals(SDate.now()), 1 second) match {
      case _: ArrivalsFeedFailure => true
      case _ => false
    }

    result === true
  }

  "Given a mock feed requester that throws an exception " +
    "I should get an ArrivalsFeedFailure response" >> {
    val exceptionFeed = MagFeed(privateKey, claimIss, claimRole, claimSub, () => SDate.now(), PortCode("MAN"), MockExceptionThrowingFeedRequester(() => new Exception("I'm throwing an exception")))

    val isFeedFailure = Await.result(exceptionFeed.requestArrivals(SDate.now()), 1 second) match {
      case ArrivalsFeedFailure(_, _) => true
      case _ => false
    }

    isFeedFailure must_== true
  }

  "Given a prod feed requester and empty feed parameters " +
    "I should get an ArrivalsFeedFailure response" >> {
    val exceptionFeed = MagFeed("", "", "", "", () => SDate.now(), PortCode("MAN"), ProdFeedRequester)

    val isFeedFailure = Await.result(exceptionFeed.requestArrivals(SDate.now()), 1 second) match {
      case ArrivalsFeedFailure(_, _) => true
      case _ => false
    }

    isFeedFailure must_== true
  }

  def jsonResponseSingleArrival: String =
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
      |        "domesticInternational": "International",
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

  def jsonResponseSingleArrivalWith0Pax: String =
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
      |        "domesticInternational": "International",
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
      |            "maximum": 0,
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
