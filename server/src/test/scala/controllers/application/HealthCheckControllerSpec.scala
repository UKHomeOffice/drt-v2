package controllers.application

import akka.actor.{Actor, ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
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
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.config.Lhr
import uk.gov.homeoffice.drt.service.ApplicationService
import uk.gov.homeoffice.drt.testsystem.TestDrtSystem
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import scala.concurrent.ExecutionContextExecutor

class HealthCheckControllerSpec extends PlaySpec with BeforeAndAfterEach {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)

  val splits: Splits = Splits(Set(), ApiSplitsWithHistoricalEGateAndFTPercentages, Option(DC), Percentage)
  val flights: Seq[(UtcDate, FlightsWithSplits)] = Seq(
    (UtcDate(2024, 6, 26), FlightsWithSplits(Seq(
      ApiFlightWithSplits(ArrivalGenerator.arrival(iata= "BA0001", schDt = "2024-06-26T11:30"), Set()),
      ApiFlightWithSplits(ArrivalGenerator.arrival(iata= "BA0005", schDt = "2024-06-26T11:35"), Set(splits)),
    ))),
  )

  "receivedLiveApiData(60, 1)" should {
    "return the percentage of flights landed in the past 60 minutes that have live API" in {
      val controller = newController(newDrtInterface(flights))

      val authHeader = Headers("X-Auth-Roles" -> "super-admin,LHR")
      val result = controller
        .receivedLiveApiData(60, 1)
        .apply(FakeRequest(method = "GET", uri = "", headers = authHeader, body = AnyContentAsEmpty))

      status(result) must ===(OK)
      contentAsString(result) must ===("0.5")
    }
  }

  private def newController(interface: DrtSystemInterface) =
    new HealthCheckController(Helpers.stubControllerComponents(), interface)

  private def newDrtInterface(flights: Seq[(UtcDate, FlightsWithSplits)]): DrtSystemInterface = {
    val mod = new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val airportConfig: AirportConfig = Lhr.config
    }
    implicit val ec: ExecutionContextExecutor = system.dispatcher

    new TestDrtSystem(Lhr.config, mod.mockDrtParameters, () => SDate("2024-06-26T12:00")) {
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
    }
  }
}

class MockFlightsRouterActor(flights: Seq[(UtcDate, FlightsWithSplits)]) extends Actor {
  override def receive: Receive = {
    case _ => sender() ! Source(flights)
  }
}
