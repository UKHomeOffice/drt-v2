package controllers.application

import controllers.ArrivalGenerator
import drt.shared.FeedSourceArrival
import org.apache.pekko.pattern.ask
import org.scalatestplus.play.PlaySpec
import play.api.http.Status.OK
import play.api.test.Helpers._
import play.api.test.{ FakeRequest, Helpers }
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.SDate
import upickle.default.read

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class FeedsControllerSpec extends PlaySpec {

  private def apiSplits(paxCount: Int): Splits = Splits(
    splits = Set(ApiPaxTypeAndQueueCount(PaxTypes.GBRNational, Queues.EGate, paxCount, None, None)),
    source = ApiSplitsWithHistoricalEGateAndFTPercentages,
    maybeEventType = None,
    splitStyle = SplitStyle.PaxNumbers
  )

  private val flightStateArrival: Arrival = ArrivalGenerator
    .live(
      iata = "BA0001",
      schDt = "2024-06-01T10:00",
      totalPax = Option(150),
      transPax = Option(5),
      gate = Option("G1"),
      stand = Option("S1"),
      status = ArrivalStatus("Landed"),
      estDt = "2024-06-01T10:05",
      actDt = "2024-06-01T10:07"
    )
    .toArrival(LiveFeedSource)

  "FeedSourceArrival.orderedSourcesFor" should {
    "order sources as Forecast schedule, Port forecast, Live arrivals, API, Port live" in {
      val configured = Seq(LiveFeedSource, ApiFeedSource, AclFeedSource, LiveBaseFeedSource, ForecastFeedSource)

      FeedSourceArrival.orderedSourcesFor(configured) mustBe
        Seq(AclFeedSource, ForecastFeedSource, LiveBaseFeedSource, ApiFeedSource, LiveFeedSource)
    }

    "omit sources the port isn't configured for, and sources that aren't arrival feeds" in {
      val configured = Seq(ApiFeedSource, LiveFeedSource, HistoricApiFeedSource, MlFeedSource)

      FeedSourceArrival.orderedSourcesFor(configured) mustBe Seq(ApiFeedSource, LiveFeedSource)
    }
  }

  "apiFeedSourceArrival" should {
    "return None when the flight has no API passenger numbers" in {
      FeedsController.apiFeedSourceArrival(flightStateArrival) mustBe None
    }

    "return an API arrival carrying only the API passenger numbers and no other feeds' operational data" in {
      val apiPax = Passengers(Option(123), Option(7))
      val withApi = flightStateArrival.copy(
        PassengerSources = flightStateArrival.PassengerSources + (ApiFeedSource -> apiPax)
      )

      val result = FeedsController.apiFeedSourceArrival(withApi)

      result.map(_.feedSource) mustBe Some(ApiFeedSource)
      result.map(_.arrival.PassengerSources) mustBe Some(Map(ApiFeedSource -> apiPax))
      result.map(_.arrival.bestPaxEstimate(List(LiveFeedSource, ApiFeedSource)).passengers) mustBe Some(apiPax)
      result.map(_.arrival.unique) mustBe Some(withApi.unique)
      result.map(a => (a.arrival.Gate, a.arrival.Stand, a.arrival.Estimated, a.arrival.Actual)) mustBe
        Some((None, None, None, None))
    }
  }

  "FeedsController" should {

    val drtSystemInterface = new TestDrtModule().provideDrtSystemInterface

    implicit val mat = drtSystemInterface.materializer

    val requestHeaders = Seq(
      "X-Forwarded-Email" -> "test@test.com",
      "X-Forwarded-Preferred-Username" -> "test",
      "X-Forwarded-User" -> "test"
    )

    "get feed status" in {

      val controller = new FeedsController(Helpers.stubControllerComponents(), drtSystemInterface)

      val request = FakeRequest().withHeaders(requestHeaders :+ ("X-Forwarded-Groups" -> "TEST"): _*)

      val result = controller.getFeedStatuses.apply(request)

      status(result) mustBe OK

      val resultExpected = "[]"

      contentAsString(result) must include(resultExpected)
    }

    "include the API passenger numbers from flight state in the arrival sources" in {
      val flightsRouter = drtSystemInterface.actorService.flightsRouterActor
      val terminal = drtSystemInterface.airportConfig.terminalsForDate(SDate(flightStateArrival.Scheduled).toLocalDate).head
      val arrival = flightStateArrival.copy(Terminal = terminal)

      Await.ready(flightsRouter.ask(ArrivalsDiff(Seq(arrival), Seq())), 5.seconds)
      Await.ready(
        flightsRouter.ask(SplitsForArrivals(Map(arrival.unique -> Set(apiSplits(88))))),
        5.seconds
      )

      val controller = new FeedsController(Helpers.stubControllerComponents(), drtSystemInterface)
      val request = FakeRequest().withHeaders(requestHeaders :+ ("X-Forwarded-Groups" -> "TEST,arrival-source"): _*)
      val result = controller
        .getArrivalAtPointInTime(
          drtSystemInterface.now().millisSinceEpoch,
          arrival.VoyageNumber.numeric,
          arrival.Terminal.toString,
          arrival.Scheduled,
          arrival.Origin.iata
        )
        .apply(request)

      status(result) mustBe OK
      val sources = read[Seq[Option[FeedSourceArrival]]](contentAsString(result))

      // Test port has no port forecast feed: ACL, Cirium, API, Port live. Only API has data for this flight
      sources.map(_.map(_.feedSource)) mustBe Seq(None, None, Some(ApiFeedSource), None)
      sources.flatten.map(_.arrival.PassengerSources) mustBe Seq(Map(ApiFeedSource -> Passengers(Option(88), Option(0))))
    }
  }
}
