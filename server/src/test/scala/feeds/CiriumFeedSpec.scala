package feeds

import akka.actor.ActorSystem
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import drt.server.feeds.cirium.CiriumFeed
import drt.shared.{Arrival, CiriumFeedSource}
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

    val result = CiriumFeed.toArrival(ciriumArrival)

    result === expected
  }

  private def drtArrival(publishedArrivalTime: String, estRunwayArrival: String, actRunwayArrival: String, estGateArrivalTime: String, actGateArrivalTime: String) = {
    Arrival(
      Some("TST"),
      "Active",
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
      Option(100000),
      "LHR",
      "1",
      "TST1000",
      "TST1000",
      "JFK",
      SDate(publishedArrivalTime).millisSinceEpoch,
      None,
      Set(CiriumFeedSource)
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

  "When successfully polling for CiriumArrivals I should get a stream of ArrivalFeedSuccess" >> {
    implicit val mat: ActorMaterializer = ActorMaterializer()

    val ciriumFeed = new CiriumFeed("") with MockClientWithSuccess

    val probe = TestProbe()

    val cancellable = ciriumFeed.tickingSource.to(Sink.actorRef(probe.ref, "completed")).run()

    probe.fishForMessage(1 seconds) {
      case s: ArrivalsFeedSuccess if s.arrivals.flights.head.Scheduled == SDate("2019-07-15T11:05:00.000Z").millisSinceEpoch =>
        println(s"Successfully got a result")
        true
    }

    success
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
}