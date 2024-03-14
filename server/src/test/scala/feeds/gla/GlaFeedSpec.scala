package feeds.gla

import akka.actor.typed.ActorRef
import akka.http.scaladsl.model._
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.server.feeds._
import drt.server.feeds.gla.GlaFeed
import drt.shared.FlightsApi.Flights
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalStatus, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

case class MockFeedRequester(json: String = "[]") {

  var mockResponse: HttpResponse = HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json))

  def send: HttpRequest => Future[HttpResponse] = _ => Future(mockResponse)
}

object MockExceptionThrowingFeedRequester {
  def send: HttpRequest => Future[HttpResponse] = _ => Future(throw new Exception("Something Broke"))
}

class GlaFeedSpec extends CrunchTestLike {

  import drt.server.feeds.gla.AzinqGlaArrivalJsonFormats._

  def mockFeedWithResponse(res: String): Source[ArrivalsFeedResponse, ActorRef[Feed.FeedTick]] = AzinqFeed.source(
    Feed.actorRefSource,
    AzinqFeed(
      uri = "http://test.com",
      token = "",
      password = "",
      username = "",
      httpRequest = MockFeedRequester(res).send
    )
  )

  "Given a GLA Feed I should be able to connect to it and get arrivals back" >> {
    skipped(s"Exploratory test.")
    val prodFeed = GlaFeed(
      url = sys.env.getOrElse("GLA_LIVE_URL", ""),
      token = sys.env.getOrElse("GLA_LIVE_TOKEN", ""),
      password = sys.env.getOrElse("GLA_LIVE_PASSWORD", ""),
      username = sys.env.getOrElse("GLA_LIVE_USERNAME", ""),
    )

    prodFeed.runWith(Sink.seq)

    Thread.sleep(20000)
    true
  }

  "Given a mock json response containing a single valid flight " +
    "I should get a stream with that flight in it " >> {
    val mockFeed = mockFeedWithResponse(firstJsonExample)

    val probe = TestProbe()

    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case s: ArrivalsFeedSuccess if s.arrivals.flights.head.Scheduled == SDate("2019-11-13T12:34:00Z").millisSinceEpoch => true
      case _ => false
    }

    success
  }

  "Given a mock json response containing feed with an arrival and a departure " +
    "I should only get the arrival in the end result " >> {
    val dsd = "2019-11-13T17:34:00+00:00"
    val mockFeed = mockFeedWithResponse(containingADepartureJson(dsd))

    val probe = TestProbe()

    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedSuccess(Flights(a), _) if a.size == 1 && !a.exists(_.Scheduled == SDate(dsd).millisSinceEpoch) => true
      case _ => false
    }

    success
  }

  "Given a mock json response containing invalid json " +
    "I should get an ArrivalsFeedFailure" >> {
    val mockFeed = mockFeedWithResponse("bad json")

    val probe = TestProbe()

    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedFailure(_, _) => true
      case _ => false
    }

    success
  }

  "Given a feed connection failure then I should get back an ArrivalsFeedFailure." >> {
    val mockFeed = AzinqFeed.source(
      Feed.actorRefSource,
      AzinqFeed(
        uri = "http://test.com",
        token = "",
        password = "",
        username = "",
        httpRequest = MockExceptionThrowingFeedRequester.send
      )
    )

    val probe = TestProbe()

    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedFailure(_, _) => true
      case _ => false
    }

    success
  }

  "Given some valid GLA Feed Json I should get back a valid Arrival object" >> {
    val mockFeed = mockFeedWithResponse(firstJsonExample)

    val expected = Arrival(
      Operator = None,
      Status = ArrivalStatus("Flight is on schedule"),
      Estimated = Some(SDate("2019-11-13T13:32:00Z").millisSinceEpoch),
      Predictions = Predictions(0L, Map()),
      Actual = Some(SDate("2019-11-13T13:31:00Z").millisSinceEpoch),
      EstimatedChox = Some(SDate("2019-11-13T12:33:00Z").millisSinceEpoch),
      ActualChox = Some(SDate("2019-11-13T13:30:00Z").millisSinceEpoch),
      Gate = Some("G"),
      Stand = Some("ST"),
      MaxPax = Some(50),
      RunwayID = Some("3"),
      BaggageReclaimId = Some("2"),
      AirportID = PortCode("GLA"),
      Terminal = T1,
      rawICAO = "TST234",
      rawIATA = "TS234",
      Origin = PortCode("TST"),
      Scheduled = SDate("2019-11-13T12:34:00Z").millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set(LiveFeedSource),
      CarrierScheduled = None,
      PassengerSources = Map(LiveFeedSource -> Passengers(Some(20), None))
    )
    val probe = TestProbe()
    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedSuccess(Flights(arrival :: Nil), _) => arrival === expected
      case _ => false
    }

    success
  }

  "Given a different arrival with valid GLA Feed Json I should get back a valid Arrival object" >> {
    val mockFeed = mockFeedWithResponse(secondJsonExample)

    val expected = Arrival(
      Operator = None,
      Status = ArrivalStatus("Flight is cancelled"),
      Estimated = None,
      Predictions = Predictions(0L, Map()),
      Actual = Some(SDate("2019-11-14T14:41:00Z").millisSinceEpoch),
      EstimatedChox = Some(SDate("2019-11-14T12:44:00Z").millisSinceEpoch),
      ActualChox = Some(SDate("2019-11-14T14:40:00Z").millisSinceEpoch),
      Gate = Some("GATE"),
      Stand = Some("STAND"),
      MaxPax = Some(75),
      RunwayID = Some("4"),
      BaggageReclaimId = Some("2"),
      AirportID = PortCode("GLA"),
      Terminal = T1,
      rawICAO = "TTT244",
      rawIATA = "TT244",
      Origin = PortCode("TTT"),
      Scheduled = SDate("2019-11-14T12:44:00Z").millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set(LiveFeedSource),
      CarrierScheduled = None,
      PassengerSources = Map(LiveFeedSource -> Passengers(Some(55), None))
    )

    val probe = TestProbe()
    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedSuccess(Flights(arrival :: Nil), _) => arrival === expected
      case _ => false
    }

    success
  }


  "Given a GLA feed item with 0 for ActPax and MaxPax then we should 0 in the arrival" >> {
    val mockFeed = mockFeedWithResponse(exampleWith0s)

    val probe = TestProbe()
    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedSuccess(Flights(arrival :: Nil), _) =>
        (arrival.PassengerSources.get(LiveFeedSource).flatMap(_.actual), arrival.MaxPax) === ((Some(0), Some(0)))
      case _ => false
    }

    success
  }


  "Given a different arrival with only required JSON fields then I should still get an arrival object with those fields" >> {
    val mockFeed = mockFeedWithResponse(requiredFieldsOnlyJson)

    val expected = Arrival(
      Operator = None,
      Status = ArrivalStatus("Flight is cancelled"),
      Estimated = None,
      Predictions = Predictions(0L, Map()),
      Actual = None,
      EstimatedChox = None,
      ActualChox = None,
      Gate = None,
      Stand = None,
      MaxPax = None,
      RunwayID = None,
      BaggageReclaimId = None,
      AirportID = PortCode("GLA"),
      Terminal = T1,
      rawICAO = "TTT244",
      rawIATA = "TT244",
      Origin = PortCode("TTT"),
      Scheduled = SDate("2019-11-14T12:44:00Z").millisSinceEpoch,
      PcpTime = None,
      FeedSources = Set(LiveFeedSource),
      CarrierScheduled = None,
      PassengerSources = Map(LiveFeedSource -> Passengers(None, None))
    )

    val probe = TestProbe()
    val actorSource = mockFeed.to(Sink.actorRef(probe.ref, StreamCompleted)).run()
    actorSource ! Feed.Tick

    probe.fishForMessage(1.seconds) {
      case ArrivalsFeedSuccess(Flights(arrival :: Nil), _) => arrival === expected
      case _ => false
    }

    success
  }

  def firstJsonExample: String =
    """[{
      |        "AIBT": "2019-11-13T13:30:00+00:00",
      |        "AirlineIATA": "TS",
      |        "AirlineICAO": "TST",
      |        "ALDT": "2019-11-13T13:31:00+00:00",
      |        "AODBProbableDateTime": "2019-11-13T13:32:00+00:00",
      |        "CarouselCode": "2",
      |        "CodeShareFlights": "",
      |        "CodeShareInd": "N",
      |        "DepartureArrivalType": "A",
      |        "EIBT": "2019-11-13T12:33:00+00:00",
      |        "FlightNumber": "234",
      |        "FlightStatus": "S",
      |        "FlightStatusDesc": "Flight is on schedule",
      |        "GateCode": "G",
      |        "MaxPax": 50,
      |        "OriginDestAirportIATA": "TST",
      |        "OriginDestAirportICAO": "TSTT",
      |        "PaxEstimated": null,
      |        "Runway": "3",
      |        "ScheduledDateTime": "2019-11-13T12:34:00+00:00",
      |        "StandCode": "ST",
      |        "TerminalCode": "T1",
      |        "TotalPassengerCount": 20
      |}]""".stripMargin

  def secondJsonExample: String =
    """[{
      |        "AIBT": "2019-11-14T14:40:00+00:00",
      |        "AirlineIATA": "TT",
      |        "AirlineICAO": "TTT",
      |        "ALDT": "2019-11-14T14:41:00+00:00",
      |        "AODBProbableDateTime": null,
      |        "CarouselCode": "2",
      |        "CodeShareFlights": "",
      |        "CodeShareInd": "N",
      |        "DepartureArrivalType": "A",
      |        "EIBT": "2019-11-14T12:44:00+00:00",
      |        "FlightNumber": "244",
      |        "FlightStatus": "C",
      |        "FlightStatusDesc": "Flight is cancelled",
      |        "GateCode": "GATE",
      |        "MaxPax": 75,
      |        "OriginDestAirportIATA": "TTT",
      |        "OriginDestAirportICAO": "TTTT",
      |        "PaxEstimated": null,
      |        "Runway": "4",
      |        "ScheduledDateTime": "2019-11-14T12:44:00+00:00",
      |        "StandCode": "STAND",
      |        "TerminalCode": "T1",
      |        "TotalPassengerCount": 55
      |}]""".stripMargin

  def exampleWith0s: String =
    """[{
      |        "AIBT": "2019-11-14T14:40:00+00:00",
      |        "AirlineIATA": "TT",
      |        "AirlineICAO": "TTT",
      |        "ALDT": "2019-11-14T14:41:00+00:00",
      |        "AODBProbableDateTime": null,
      |        "CarouselCode": "2",
      |        "CodeShareFlights": "",
      |        "CodeShareInd": "N",
      |        "DepartureArrivalType": "A",
      |        "EIBT": "2019-11-14T12:44:00+00:00",
      |        "FlightNumber": "244",
      |        "FlightStatus": "C",
      |        "FlightStatusDesc": "Flight is cancelled",
      |        "GateCode": "GATE",
      |        "MaxPax": 0,
      |        "OriginDestAirportIATA": "TTT",
      |        "OriginDestAirportICAO": "TTTT",
      |        "PaxEstimated": null,
      |        "Runway": "4",
      |        "ScheduledDateTime": "2019-11-14T12:44:00+00:00",
      |        "StandCode": "STAND",
      |        "TerminalCode": "T1",
      |        "TotalPassengerCount": 0
      |}]""".stripMargin

  def requiredFieldsOnlyJson: String =
    """[{
      |        "AIBT": null,
      |        "AirlineIATA": "TT",
      |        "AirlineICAO": "TTT",
      |        "ALDT": null,
      |        "AODBProbableDateTime": null,
      |        "CarouselCode": null,
      |        "CodeShareFlights": null,
      |        "CodeShareInd": null,
      |        "DepartureArrivalType": "A",
      |        "EIBT": null,
      |        "FlightNumber": "244",
      |        "FlightStatus": "C",
      |        "FlightStatusDesc": "Flight is cancelled",
      |        "GateCode": null,
      |        "MaxPax": null,
      |        "OriginDestAirportIATA": "TTT",
      |        "OriginDestAirportICAO": "TTTT",
      |        "PaxEstimated": null,
      |        "Runway": null,
      |        "ScheduledDateTime": "2019-11-14T12:44:00+00:00",
      |        "StandCode": null,
      |        "TerminalCode": "T1",
      |        "TotalPassengerCount": null
      |}]""".stripMargin

  def containingADepartureJson(domesticScheduledTime: String): String =
    s"""[{
       |      "AIBT": "2019-11-13T13:30:00+00:00",
       |      "AirlineIATA": "TS",
       |      "AirlineICAO": "TST",
       |      "ALDT": "2019-11-13T13:31:00+00:00",
       |      "AODBProbableDateTime": "2019-11-13T13:32:00+00:00",
       |      "CarouselCode": "2",
       |      "CodeShareFlights": "",
       |      "CodeShareInd": "N",
       |      "DepartureArrivalType": "A",
       |      "EIBT": "2019-11-13T12:33:00+00:00",
       |      "FlightNumber": "234",
       |      "FlightStatus": "S",
       |      "FlightStatusDesc": "Flight is on schedule",
       |      "GateCode": "G",
       |      "MaxPax": 50,
       |      "OriginDestAirportIATA": "TST",
       |      "OriginDestAirportICAO": "TSTT",
       |      "PaxEstimated": null,
       |      "Runway": "3",
       |      "ScheduledDateTime": "2019-11-13T12:34:00+00:00",
       |      "StandCode": "ST",
       |      "TerminalCode": "T1",
       |      "TotalPassengerCount": 20
       |},
       |{
       |      "AIBT": null,
       |      "AirlineIATA": "TT",
       |      "AirlineICAO": "TTT",
       |      "ALDT": null,
       |      "AODBProbableDateTime": null,
       |      "CarouselCode": null,
       |      "CodeShareFlights": null,
       |      "CodeShareInd": null,
       |      "DepartureArrivalType": "D",
       |      "EIBT": null,
       |      "FlightNumber": "244",
       |      "FlightStatus": "C",
       |      "FlightStatusDesc": "Flight is cancelled",
       |      "GateCode": null,
       |      "MaxPax": null,
       |      "OriginDestAirportIATA": "TTT",
       |      "OriginDestAirportICAO": "TTTT",
       |      "PaxEstimated": null,
       |      "Runway": null,
       |      "ScheduledDateTime": "$domesticScheduledTime",
       |      "StandCode": null,
       |      "TerminalCode": "T1",
       |      "TotalPassengerCount": null
       |}]""".stripMargin

}
