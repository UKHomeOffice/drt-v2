package feeds.cirium

import actors.acking.AckingReceiver.StreamCompleted
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import drt.server.feeds.cirium.CiriumFeed
import drt.shared.Terminals.T1
import drt.shared.{Arrival, ArrivalStatus, LiveBaseFeedSource, Operator, PortCode}
import org.specs2.mock.Mockito
import org.specs2.mutable.SpecificationLike
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import uk.gov.homeoffice.cirium.services.entities._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

class CiriumFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike with Mockito {
  sequential
  isolated

  "When rounding times to the nearest 5 minutes" >> {

    def convert(s: String) = CiriumFeed
      .timeToNearest5Minutes(SDate(s)).toISOString()

    "A time with minutes ending in a 5 should be unchanged" >> {
      convert("2019-07-15T11:15:00Z") mustEqual "2019-07-15T11:15:00Z"
    }
    "A time with minutes ending in a 0 should be unchanged" >> {
      convert("2019-07-15T11:10:00Z") mustEqual "2019-07-15T11:10:00Z"
    }
    "A time with minutes ending in a 1 should be rounded down" >> {
      convert("2019-07-15T11:11:00Z") mustEqual "2019-07-15T11:10:00Z"
    }
    "A time with minutes ending in a 2 should be rounded down" >> {
      convert("2019-07-15T11:12:00Z") mustEqual "2019-07-15T11:10:00Z"
    }
    "A time with minutes ending in a 3 should be rounded up" >> {
      convert("2019-07-15T11:13:00Z") mustEqual "2019-07-15T11:15:00Z"
    }
    "A time with minutes ending in a 4 should be rounded up" >> {
      convert("2019-07-15T11:14:00Z") mustEqual "2019-07-15T11:15:00Z"
    }
    "A time with minutes ending in a 6 should be rounded down" >> {
      convert("2019-07-15T11:16:00Z") mustEqual "2019-07-15T11:15:00Z"
    }
    "A time with minutes ending in a 9 should be rounded up" >> {
      convert("2019-07-15T11:19:00Z") mustEqual "2019-07-15T11:20:00Z"
    }

  }

  "Given a CiriumFlightStatus I should be able to parse it to an equivelant DRT Arrival Instance" >> {
    val publishedArrivalTime = "2019-07-15T11:05:00.000Z"
    val estRunwayArrival = "2019-07-15T11:07:00.000Z"
    val actRunwayArrival = "2019-07-15T11:08:00.000Z"
    val estGateArrivalTime = "2019-07-15T11:09:00.000Z"
    val actGateArrivalTime = "2019-07-15T11:10:00.000Z"

    val ciriumArrival = ciriumFlightStatus(
      publishedArrivalTime,
      estRunwayArrival,
      actRunwayArrival,
      estGateArrivalTime,
      actGateArrivalTime,
      "1000"
    )

    val expected = drtArrival(publishedArrivalTime, estRunwayArrival, actRunwayArrival, estGateArrivalTime, actGateArrivalTime)

    val result = CiriumFeed.toArrival(ciriumArrival, PortCode("LHR"))

    result === expected
  }

  "Given a CiriumFlightStatus with a non round scheduled time " +
    "Then I should get a rounded scheduled time back and the cirium scheduled time should be in CarrierScheduled" >> {
    val publishedArrivalTime = "2019-07-15T11:06:00.000Z"
    val publishedArrivalTimeRounded = "2019-07-15T11:05:00.000Z"
    val estRunwayArrival = "2019-07-15T11:07:00.000Z"
    val actRunwayArrival = "2019-07-15T11:08:00.000Z"
    val estGateArrivalTime = "2019-07-15T11:09:00.000Z"
    val actGateArrivalTime = "2019-07-15T11:10:00.000Z"

    val ciriumArrival = ciriumFlightStatus(
      publishedArrivalTime,
      estRunwayArrival,
      actRunwayArrival,
      estGateArrivalTime,
      actGateArrivalTime,
      "1000"
    )

    val expected = drtArrival(
      publishedArrivalTimeRounded,
      estRunwayArrival,
      actRunwayArrival,
      estGateArrivalTime,
      actGateArrivalTime
    ).copy(CarrierScheduled = Option(SDate(publishedArrivalTime).millisSinceEpoch))

    val result = CiriumFeed.toArrival(ciriumArrival, PortCode("LHR"))

    result === expected
  }

  private def drtArrival(publishedArrivalTime: String, estRunwayArrival: String, actRunwayArrival: String, estGateArrivalTime: String, actGateArrivalTime: String) = {
    Arrival(
      Option(Operator("TST")),
      ArrivalStatus("Active"),
      Option(SDate(estRunwayArrival).millisSinceEpoch),
      Option(SDate(actRunwayArrival).millisSinceEpoch),
      Option(SDate(estGateArrivalTime).millisSinceEpoch),
      Option(SDate(actGateArrivalTime).millisSinceEpoch),
      Option("22"),
      None,
      None,
      None,
      None,
      None,
      Option("12"),
      PortCode("LHR"),
      T1,
      "TST1000",
      "TST1000",
      PortCode("JFK"),
      SDate(publishedArrivalTime).millisSinceEpoch,
      None,
      Set(LiveBaseFeedSource)
    )
  }

  private def ciriumFlightStatus(
                                  publishedArrivalTime: String,
                                  estRunwayArrival: String,
                                  actRunwayArrival: String,
                                  estGateArrivalTime: String,
                                  actGateArrivalTime: String,
                                  flightNumber: String
                                ) = {
    CiriumFlightStatus(
      100000,
      "TST",
      "TST",
      "TST",
      flightNumber,
      "JFK",
      "LHR",
      CiriumDate("2019-07-15T09:10:00.000Z", None),
      CiriumDate(publishedArrivalTime, None),
      "A",
      CiriumOperationalTimes(
        publishedDeparture = None,
        scheduledGateDeparture = None,
        estimatedGateDeparture = None,
        actualGateDeparture = None,
        flightPlanPlannedDeparture = None,
        scheduledRunwayDeparture = None,
        estimatedRunwayDeparture = None,
        actualRunwayDeparture = None,
        publishedArrival = Option(CiriumDate(publishedArrivalTime, None)),
        flightPlanPlannedArrival = None,
        scheduledGateArrival = Option(CiriumDate(publishedArrivalTime, None)),
        estimatedGateArrival = Option(CiriumDate(estGateArrivalTime, None)),
        actualGateArrival = Option(CiriumDate(actGateArrivalTime, None)),
        scheduledRunwayArrival = None,
        estimatedRunwayArrival = Option(CiriumDate(estRunwayArrival, None)),
        actualRunwayArrival = Option(CiriumDate(actRunwayArrival, None))),
      None,
      None,
      List(CiriumCodeshare("CZ", "1000", "L"), CiriumCodeshare("DL", "2000", "L")),
      Option(CiriumAirportResources(
        departureTerminal = Option("1"),
        departureGate = None,
        arrivalTerminal = Option("1"),
        arrivalGate = Option("22"),
        baggage = Option("12"))
      ),
      Seq())
  }


  private val basicCiriumFlightStatus = {
    CiriumFlightStatus(
      100000,
      "TST",
      "TST",
      "TST",
      "100",
      "JFK",
      "LHR",
      CiriumDate("2019-07-15T09:10:00.000Z", None),
      CiriumDate("2019-07-16T09:10:00.000Z", None),
      "A",
      CiriumOperationalTimes(
        publishedDeparture = None,
        scheduledGateDeparture = None,
        estimatedGateDeparture = None,
        actualGateDeparture = None,
        flightPlanPlannedDeparture = None,
        scheduledRunwayDeparture = None,
        estimatedRunwayDeparture = None,
        actualRunwayDeparture = None,
        publishedArrival = Option(CiriumDate("2019-07-16T09:10:00.000Z", None)),
        flightPlanPlannedArrival = None,
        scheduledGateArrival = None,
        estimatedGateArrival = None,
        actualGateArrival = None,
        scheduledRunwayArrival = None,
        estimatedRunwayArrival = None,
        actualRunwayArrival = None),
      None,
      None,
      List(),
      None,
      Seq())
  }

  "When successfully polling for CiriumArrivals I should get a stream of ArrivalFeedSuccess" >> {
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val ciriumFeed = new CiriumFeed("", PortCode("LHR")) with MockClientWithSuccess

    val probe = TestProbe()

    ciriumFeed.tickingSource(30 seconds).to(Sink.actorRef(probe.ref, StreamCompleted)).run()

    probe.fishForMessage(1 seconds) {
      case s: ArrivalsFeedSuccess if s.arrivals.flights.head.Scheduled == SDate("2019-07-15T11:05:00.000Z").millisSinceEpoch =>
        println(s"Successfully got a result")
        true
    }

    success
  }


  "When an error occurs polling for cirium then it should continue to receive a later update" >> {
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val ciriumFeed = new CiriumFeed("", PortCode("LHR")) with MockClientWithFailure

    val probe = TestProbe()

    ciriumFeed.tickingSource(100 milliseconds).to(Sink.actorRef(probe.ref, StreamCompleted)).run()

    probe.fishForMessage(2 seconds) {
      case s: ArrivalsFeedSuccess if s.arrivals.flights.nonEmpty && s.arrivals.flights.head.Scheduled == SDate("2019-07-15T11:05:00.000Z").millisSinceEpoch =>
        println(s"Successfully got a result")
        true
      case _ => false
    }

    success
  }

  "Given a flight with an estimated touchdown, no estimated chox time, and scheduledTaxiInMinutes" +
    " we should calculate estimated chox time" >> {
    val estimatedRunwayArrivalTime = "2019-07-15T11:05:00.000Z"
    val ciriumFlight = basicCiriumFlightStatus
      .copy(
        operationalTimes = basicCiriumFlightStatus
          .operationalTimes
          .copy(estimatedRunwayArrival = Option(CiriumDate(estimatedRunwayArrivalTime, None))),
        flightDurations = Option(CiriumFlightDurations(None, None, None, None, None, None, Option(5), None))
      )

    val arrival = CiriumFeed.toArrival(ciriumFlight, PortCode("STN"))
    val result = arrival.EstimatedChox
    val expected = Option(SDate(estimatedRunwayArrivalTime).addMinutes(5).millisSinceEpoch)

    result === expected
  }

  "Given a flight with an actual touchdown, no estimated chox time, and scheduledTaxiInMinutes" +
    " we should calculate estimated chox time" >> {
    val actualRunwayTime = "2019-07-15T11:05:00.000Z"
    val ciriumFlight = basicCiriumFlightStatus
      .copy(
        operationalTimes = basicCiriumFlightStatus
          .operationalTimes
          .copy(actualRunwayArrival = Option(CiriumDate(actualRunwayTime, None))),
        flightDurations = Option(CiriumFlightDurations(None, None, None, None, None, None, Option(5), None))
      )

    val arrival = CiriumFeed.toArrival(ciriumFlight, PortCode("STN"))
    val result = arrival.EstimatedChox
    val expected = Option(SDate(actualRunwayTime).addMinutes(5).millisSinceEpoch)

    result === expected
  }

  "Given a flight with an estimated chox time, no estimated touch down time, and scheduledTaxiInMinutes " +
    "we should calculate estimated touchdown time" >> {
    val estimatedChoxTime = "2019-07-15T11:05:00.000Z"
    val ciriumFlight = basicCiriumFlightStatus
      .copy(
        operationalTimes = basicCiriumFlightStatus
          .operationalTimes
          .copy(estimatedGateArrival = Option(CiriumDate(estimatedChoxTime, None))),
        flightDurations = Option(CiriumFlightDurations(None, None, None, None, None, None, Option(5), None))
      )

    val arrival = CiriumFeed.toArrival(ciriumFlight, PortCode("STN"))
    val result = arrival.Estimated
    val expected = Option(SDate(estimatedChoxTime).addMinutes(-5).millisSinceEpoch)

    result === expected
  }

  trait MockClientWithSuccess {
    self: CiriumFeed =>
    val publishedArrivalTime = "2019-07-15T11:05:00.000Z"
    val estRunwayArrival = "2019-07-15T11:07:00.000Z"
    val actRunwayArrival = "2019-07-15T11:08:00.000Z"
    val estGateArrivalTime = "2019-07-15T11:09:00.000Z"
    val actGateArrivalTime = "2019-07-15T11:10:00.000Z"

    override def makeRequest(): Future[List[CiriumFlightStatus]] = Future(List(ciriumFlightStatus(
      publishedArrivalTime,
      estRunwayArrival,
      actRunwayArrival,
      estGateArrivalTime,
      actGateArrivalTime,
      "1000"
    )))
  }

  trait MockClientWithFailure {
    self: CiriumFeed =>
    val publishedArrivalTime = "2019-07-15T11:05:00.000Z"
    val estRunwayArrival = "2019-07-15T11:07:00.000Z"
    val actRunwayArrival = "2019-07-15T11:08:00.000Z"
    val estGateArrivalTime = "2019-07-15T11:09:00.000Z"
    val actGateArrivalTime = "2019-07-15T11:10:00.000Z"

    var callCount = 0

    override def makeRequest(): Future[List[CiriumFlightStatus]] = {
      val result = if (callCount == 0) {
        Future(List())
      } else if (callCount == 1) {
        Future(throw new Exception("Hello"))
      } else {
        Future(List(ciriumFlightStatus(
          publishedArrivalTime,
          estRunwayArrival,
          actRunwayArrival,
          estGateArrivalTime,
          actGateArrivalTime,
          "1000"
        )))
      }
      callCount = callCount + 1
      result
    }
  }

}
