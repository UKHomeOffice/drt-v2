package drt.server.feeds.edi

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes.OK
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.stream.Materializer
import drt.server.feeds.AzinqFeed
import org.specs2.mutable.Specification
import spray.json._
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.A2

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class AzinqEdiFeedSpec extends Specification {
  val system: ActorSystem = ActorSystem("azinq-edi")

  import drt.server.feeds.edi.AzinqEdiArrivalJsonFormats._

  "Given some json containing an edi flight" >> {
    "I should be able to parse it to an Arrival" >> {
      val arrivals = json("T1", "A").parseJson.convertTo[List[AzinqEdiArrival]].map(_.toArrival)

      arrivals === List(arrival)
    }
  }

  "Given a mock http response containing an edi flight json string" >> {
    "The AzinqFeed should parse the response to a list containing the arrival when the terminal is t1" >> {
      import scala.concurrent.ExecutionContext.Implicits.global
      implicit val mat: Materializer = Materializer(system)

      val feed = AzinqFeed("fake-uri", "", "", "", _ => Future.successful(HttpResponse(OK, Seq(), HttpEntity(ContentTypes.`application/json`, json("T1", "A")))))

      Await.result(feed(), 1.second) === List(arrival)
    }
    "The AzinqFeed should ignore the arrival when the terminal is frt" >> {
      import scala.concurrent.ExecutionContext.Implicits.global
      implicit val mat: Materializer = Materializer(system)

      val feed = AzinqFeed("fake-uri", "", "", "", _ => Future.successful(HttpResponse(OK, Seq(), HttpEntity(ContentTypes.`application/json`, json("FRT", "A")))))

      Await.result(feed(), 1.second) === List()
    }
    "The AzinqFeed should ignore the arrival when the departure-arrival type is departure (D)" >> {
      import scala.concurrent.ExecutionContext.Implicits.global
      implicit val mat: Materializer = Materializer(system)

      val feed = AzinqFeed("fake-uri", "", "", "", _ => Future.successful(HttpResponse(OK, Seq(), HttpEntity(ContentTypes.`application/json`, json("T1", "D")))))

      Await.result(feed(), 1.second) === List()
    }
  }

  private def arrival: LiveArrival = LiveArrival(
    operator = None,
    maxPax = Some(0),
    totalPax = None,
    transPax = None,
    terminal = A2,
    voyageNumber = 6566,
    carrierCode = "ZT",
    flightCodeSuffix = None,
    origin = "EMA",
    scheduled = 1694669400000L,
    estimated = None,
    touchdown = Some(1694669040000L),
    estimatedChox = None,
    actualChox = Some(1694671560000L),
    status = "A",
    gate = Some(""),
    stand = None,
    runway = None,
    baggageReclaim = Some(""),
  )


  def json(terminal: String, departureArrivalType: String): String =
    s"""[
       |  {
       |    "AIBT": "2023-09-14T07:06:00+01:00",
       |    "AircraftTypeDesc": "BOEING 767-200 FREIGHTER",
       |    "AircraftTypeIATA": "76X",
       |    "AircraftTypeICAO": "B762",
       |    "AirlineIATA": "ZT",
       |    "AirlineICAO": "AWC",
       |    "AirlineTicketed": "AWC",
       |    "ALDT": "2023-09-14T06:24:00+01:00",
       |    "AOBT": null,
       |    "AODBLinkedFlightId": 3071080,
       |    "ATOT": null,
       |    "BoardingCompleteDateTime": null,
       |    "BoardingStartDateTime": null,
       |    "CarouselCode": "",
       |    "CheckInFrom": "",
       |    "CheckInTo": "",
       |    "CodeShareFlights": "",
       |    "CodeShareInd": "N",
       |    "CodeSharePrimaryFlightId": null,
       |    "DepartureArrivalType": "$departureArrivalType",
       |    "EstimatedDateTime": null,
       |    "FirstBagDateTime": null,
       |    "FlightIsCancelled": 0,
       |    "FlightNumber": "6566",
       |    "FlightStatus": "A",
       |    "GateActionCode": "",
       |    "GateChangeIndicator": null,
       |    "GateCode": "",
       |    "InternationalStatus": "D",
       |    "MaxPax": 0,
       |    "OriginDestAirportIATA": "EMA",
       |    "PublishedFlightId": 2967689,
       |    "Registration": "OYSRH",
       |    "Runway": "24",
       |    "ScheduledDateTime": "2023-09-14T06:30:00+01:00",
       |    "ServiceType": "F",
       |    "TerminalCode": "$terminal",
       |    "TotalPassengerCount": null,
       |    "ZoneDateTime": "2023-09-14T06:14:00+01:00"
       |  }
       |]
       |""".stripMargin
}
