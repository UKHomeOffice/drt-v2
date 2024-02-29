package controllers.application

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.{CrunchMinute, CrunchMinutes, MinutesContainer}
import drt.shared.TQM
import module.DRTModule
import org.scalatest.BeforeAndAfterEach
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, Headers}
import play.api.test.Helpers._
import play.api.test._
import providers.FlightsProvider
import uk.gov.homeoffice.drt.arrivals.EventTypes.DC
import uk.gov.homeoffice.drt.arrivals.SplitStyle.Percentage
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Splits}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.EeaDesk
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.ApplicationService
import uk.gov.homeoffice.drt.testsystem.{TestActorService, TestDrtSystem}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.ExecutionContextExecutor

class HealthCheckControllerSpec extends PlaySpec with BeforeAndAfterEach {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)

  val now: () => SDateLike = () => SDate("2024-06-26T12:00")

  val splits: Splits = Splits(Set(), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC), Percentage)
  val flights: Seq[(UtcDate, FlightsWithSplits)] = Seq(
    (UtcDate(2024, 6, 26), FlightsWithSplits(Seq(
      ApiFlightWithSplits(ArrivalGenerator.arrival(iata= "BA0001", schDt = "2024-06-26T11:30"), Set(), lastUpdated = Option(SDate("2024-06-26T05:40").millisSinceEpoch)),
      ApiFlightWithSplits(ArrivalGenerator.arrival(iata= "BA0005", schDt = "2024-06-26T11:35", actDt = "2024-06-26T11:40"), Set(splits), lastUpdated = Option(SDate("2024-06-26T11:50").millisSinceEpoch)),
      ApiFlightWithSplits(ArrivalGenerator.arrival(iata= "BA0011", schDt = "2024-06-26T12:30"), Set(), lastUpdated = Option(SDate("2024-06-26T05:40").millisSinceEpoch)),
      ApiFlightWithSplits(ArrivalGenerator.arrival(iata= "BA0015", schDt = "2024-06-26T12:35", actDt = "2024-06-26T12:45"), Set(splits), lastUpdated = Option(SDate("2024-06-26T11:50").millisSinceEpoch)),
    ))),
  )

  val minutes: Seq[(UtcDate, MinutesContainer[CrunchMinute, TQM])] = Seq(
    (UtcDate(2024, 6, 16), MinutesContainer(Seq(
      CrunchMinute(T1, EeaDesk, SDate("2024-06-26T12:30").millisSinceEpoch, 0d, 0d, 0, 0, None, None, None, None, None, None, lastUpdated = Option(SDate("2024-06-26T11:50").millisSinceEpoch)),
    )))
  )

  val controller: HealthCheckController = newController(newDrtInterface(flights, minutes))

  "receivedLiveApiData(60, 1)" should {
    "return the percentage of flights landed in the past 60 minutes that have live API" in {

      val authHeader = Headers("X-Auth-Roles" -> "super-admin,LHR")
      val result = controller
        .receivedLiveApiData(60, 1)
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===("50.0")
    }
  }

  "receivedLandingTimes(60, 1)" should {
    "return the percentage of flights scheduled to land in the past 60 minutes that have an actual landing time" in {
      val authHeader = Headers("X-Auth-Roles" -> "super-admin,LHR")
      val result = controller
        .receivedLandingTimes(60, 1)
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===("50.0")
    }
  }

  "receivedUpdates(60, 1)" should {
    "return the percentage of flights scheduled to land in the past 60 minutes that have been updated in the past 30 minutes" in {
      val authHeader = Headers("X-Auth-Roles" -> "super-admin,LHR")
      val result = controller
        .receivedUpdates(60, 1, 30)
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===("50.0")
    }
  }

  "deskUpdates" should {
    "return true if we have some crunch minutes with a last updated time in the past 10 minutes" in {
      val authHeader = Headers("X-Auth-Roles" -> "super-admin,LHR")
      val result = controller
        .deskUpdates
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===("true")
    }
  }

  private def newController(interface: DrtSystemInterface) =
    new HealthCheckController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface(flights: Seq[(UtcDate, FlightsWithSplits)], minutes: Seq[(UtcDate, MinutesContainer[CrunchMinute, TQM])]): DrtSystemInterface = {
    val mod = new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val airportConfig: AirportConfig = Lhr.config
    }
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    new TestDrtSystem(Lhr.config, mod.mockDrtParameters, now) {
      self =>
      override lazy val applicationService: ApplicationService = new ApplicationService(
        journalType = journalType,
        airportConfig = airportConfig,
        now = now,
        params = params,
        config = config,
        db = db,
        feedService = feedService,
        manifestLookups = manifestLookups,
        manifestLookupService = manifestLookupService,
        minuteLookups = minuteLookups,
        actorService = actorService,
        persistentStateActors = persistentActors
      )(system, ec, mat, timeout) {
        override lazy val flightsProvider: FlightsProvider = FlightsProvider(system.actorOf(Props(new MockFlightsRouterActor(flights))))(timeout)
      }

      override lazy val actorService = new TestActorService(journalType,
        airportConfig,
        now,
        params.forecastMaxDays,
        flightLookups,
        minuteLookups,
      ) {
        override val queuesRouterActor: ActorRef = system.actorOf(Props(new MockQueuesRouterActor(minutes)))
      }

    }
  }
}

class MockFlightsRouterActor(flights: Seq[(UtcDate, FlightsWithSplits)]) extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Source(flights)
  }
}


class MockQueuesRouterActor(minutes: Seq[(UtcDate, MinutesContainer[CrunchMinute, TQM])]) extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Source(minutes)
  }
}
