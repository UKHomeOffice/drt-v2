package feeds.ltn

import akka.actor.Cancellable
import akka.http.scaladsl.model.{HttpEntity, HttpResponse}
import akka.stream.scaladsl.Sink
import akka.testkit.TestProbe
import drt.server.feeds.ltn.{LtnFeedRequestLike, LtnLiveFeed}
import drt.shared.Terminals.T1
import drt.shared._
import drt.shared.api.Arrival
import org.joda.time.DateTimeZone
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}


case class MockLtnRequesterWithInvalidResponse(responseValue: String)(implicit ec: ExecutionContext) extends LtnFeedRequestLike {
  override def getResponse: () => Future[HttpResponse] = () => Future(HttpResponse(entity = HttpEntity.apply(responseValue)))
}

class LtnFeedSpec extends CrunchTestLike {

  "Given an invalid response " +
    "I should get an ArrivalsFeedFailure" >> {
    val probe = TestProbe("ltn-test-probe")
    val requester = MockLtnRequesterWithInvalidResponse("Some invalid response")
    val cancellable: Cancellable = LtnLiveFeed(requester, DateTimeZone.forID("Europe/London"))
      .tickingSource(100 milliseconds)
      .to(Sink.actorRef(probe.ref, "done"))
      .run()

    probe.expectMsgClass(5 seconds, classOf[ArrivalsFeedFailure])

    cancellable.cancel()

    success
  }

  "Given a valid json response, I should correctly parse a flight out of the feed" >> {

    val jsonResponse =
      """|[{
         |    "AIBT": "2021-03-28T13:01:00+01:00",
         |    "AirlineDesc": "TEST Airline",
         |    "AirlineIATA": "TT",
         |    "AirlineICAO": "TES",
         |    "ALDT": "2021-03-28T12:20:00+01:00",
         |    "ATOT": null,
         |    "CarouselCode": "1",
         |    "DepartureArrivalType": "A",
         |    "EstimatedDateTime": "2021-03-28T13:50:00+01:00",
         |    "FlightNumber": "100",
         |    "FlightStatus": "A",
         |    "FlightStatusDesc": "Arrival is on block at a stand",
         |    "GateCode": "GG",
         |    "MaxPax": 180,
         |    "OriginDestAirportIATA": "TST",
         |    "OriginDestAirportICAO": "TEST",
         |    "PaxTransfer": null,
         |    "Runway": "25",
         |    "ScheduledDateTime": "2021-03-28T14:05:00+01:00",
         |    "StandCode": "ST1",
         |    "TerminalCode": "T1",
         |    "TotalPassengerCount": 100
         |}]""".stripMargin

    val expected = List(
      Arrival(
        Operator = Some(Operator("TEST Airline")),
        CarrierCode = CarrierCode("TT"),
        VoyageNumber = VoyageNumber(100),
        FlightCodeSuffix = None,
        Status = ArrivalStatus("Arrival is on block at a stand"),
        Estimated = Some(SDate("2021-03-28T13:50:00+01:00").millisSinceEpoch),
        Actual = Some(SDate("2021-03-28T12:20:00+01:00").millisSinceEpoch),
        EstimatedChox = None,
        ActualChox = Some(SDate("2021-03-28T13:01:00+01:00").millisSinceEpoch),
        Gate = Some("GG"),
        Stand = Some("ST1"),
        MaxPax = Some(180),
        ActPax = Some(100),
        TranPax = None,
        RunwayID = Some("25"),
        BaggageReclaimId = None,
        AirportID = PortCode("LTN"),
        Terminal = T1,
        Origin = PortCode("TST"),
        Scheduled = SDate("2021-03-28T14:05:00+01:00").millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(LiveFeedSource),
        CarrierScheduled = None,
        ApiPax = None,
        ScheduledDeparture = None)
    )

    val requester = MockLtnRequesterWithInvalidResponse(jsonResponse)

    val probe = TestProbe("ltn-test-probe")

    val cancellable: Cancellable = LtnLiveFeed(requester, DateTimeZone.forID("Etc/UTC"))
      .tickingSource(100 milliseconds)
      .to(Sink.actorRef(probe.ref, "done"))
      .run()

    val result = probe.fishForMessage(1 second) {
      case a: ArrivalsFeedSuccess => true
    }
      .asInstanceOf[ArrivalsFeedSuccess]
      .arrivals
      .flights

    cancellable.cancel()

    result === expected
  }
}
