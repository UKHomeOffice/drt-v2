package controllers.application

import actors.StreamingJournal
import akka.actor.ActorSystem
import akka.stream.Materializer
import controllers.ArrivalGenerator
import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, Headers}
import play.api.test.Helpers.{OK, contentAsString, contentType, status}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{ForecastFeedSource, LiveFeedSource, MlFeedSource}
import uk.gov.homeoffice.drt.service.FeedService
import uk.gov.homeoffice.drt.testsystem.TestDrtSystem
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class ForecastAccuracyControllerSpec extends PlaySpec {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test-1")
  implicit val mat: Materializer = Materializer(system)
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: akka.util.Timeout = 5.seconds

  "ForecastAccuracyController" should {
    val controller: ForecastAccuracyController = forecastAccuracyController(forecastPax = 120, mlPax = 150, actualPax = 100)
    "get forecast accuracy percentage" in {
      val request = FakeRequest(method = "GET", uri = "", headers = Headers(("X-Auth-Roles", "TEST")), body = AnyContentAsEmpty)

      val result = controller.getForecastAccuracy("2023-01-01").apply(request)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/plain"))
      contentAsString(result) must ===(s"""{"localDate":{"year":2023,"month":1,"day":1},"pax":[["uk.gov.homeoffice.drt.ports.Terminals.T1",{"1":[20],"14":[20],"3":[20],"30":[20],"7":[20]}]]}""".stripMargin)

    }

    "get forecast Accuracy predication csv" in {
      val request = FakeRequest(method = "GET", uri = "", headers = Headers(("X-Auth-Roles", "TEST")), body = AnyContentAsEmpty)

      val result = controller.forecastAccuracyExport(1, 1).apply(request)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(
        s"""Date,Terminal,Prediction RMSE,Legacy RMSE,Prediction Error,Legacy Error
           |2023-01-31,T1,50.000,20.000,50.000,20.000
           |""".stripMargin)
    }
  }

  private def forecastAccuracyController(forecastPax: Int, mlPax: Int, actualPax: Int) = {
    val module = new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val now = () => SDate("2023-02-01T00:00")
    }

    val drtSystemInterface: DrtSystemInterface = new TestDrtSystem(module.airportConfig, module.mockDrtParameters, module.now) {

      lazy override val feedService = new FeedService(StreamingJournal.forConfig(module.config),
        module.airportConfig,
        module.now,
        module.mockDrtParameters,
        module.config,
        paxFeedSourceOrder,
        flightLookups)(system, ec, mat, module.timeout) {
        override val forecastPaxNos: (LocalDate, SDateLike) => Future[Map[Terminal, Double]] =
          (_, _) => Future.successful(Map(Terminal("T1") -> forecastPax))

        override val actualPaxNos: LocalDate => Future[Map[Terminal, Double]] =
          _ => Future.successful(Map(Terminal("T1") -> actualPax))

        override val forecastArrivals: (LocalDate, SDateLike) => Future[Map[Terminal, Seq[Arrival]]] = (_, _) => Future.successful(
          Map(T1 -> Seq(ArrivalGenerator.arrival(iata = "BA0001",
            schDt = "2023-10-20T12:25",
            terminal = T1,
            passengerSources = Map(ForecastFeedSource -> Passengers(Some(forecastPax), None),
              MlFeedSource -> Passengers(Some(mlPax), None))))))

        override val actualArrivals: LocalDate => Future[Map[Terminal, Seq[Arrival]]] = _ => Future.successful(
          Map(T1 -> Seq(ArrivalGenerator.arrival(iata = "BA0001",
            schDt = "2023-10-20T12:25",
            terminal = T1,
            passengerSources = Map(LiveFeedSource -> Passengers(Some(actualPax), None),
              ForecastFeedSource -> Passengers(Some(forecastPax), None),
              MlFeedSource -> Passengers(Some(mlPax), None))
          )))
        )
      }
    }

    new ForecastAccuracyController(Helpers.stubControllerComponents(), drtSystemInterface)
  }
}
