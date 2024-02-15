package controllers.application

import akka.Done
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import controllers.ArrivalGenerator
import module.DRTModule
import org.scalatestplus.play.PlaySpec
import play.api.mvc.{AnyContentAsEmpty, Headers}
import play.api.test.Helpers.{OK, contentAsString, contentType, status}
import play.api.test.{FakeRequest, Helpers}
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.actor.PredictionModelActor.Models
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports.{FeedSource, ForecastFeedSource, LiveFeedSource, MlFeedSource}
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.prediction.{FeaturesWithOneToManyValues, ModelPersistence, RegressionModel}
import uk.gov.homeoffice.drt.testsystem.TestDrtSystem
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

class ForecastAccuracyControllerSpec extends PlaySpec {
  implicit val system: ActorSystem = akka.actor.ActorSystem("test")
  implicit val mat: Materializer = Materializer(system)
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: akka.util.Timeout = 5.seconds

  "ForecastAccuracyController" should {
    val liveArrivalPax = 80
    val forecastArrivalPax = 120
    val forecastArrivalTransPax = 10
    val mlPredCapPct = 75
    val maxPax = 200

    val forecastTotalPax = 120
    val mlFeedPax = 150
    val liveFeedPax = 100

    val paxSources: Map[FeedSource, Passengers] = Map(
      LiveFeedSource -> Passengers(Option(liveArrivalPax), None),
      ForecastFeedSource -> Passengers(Option(forecastArrivalPax), Option(forecastArrivalTransPax))
    )
    val arrival = ArrivalGenerator.arrival(maxPax = Option(maxPax), passengerSources = paxSources)
    val flights = FlightsWithSplits(Seq(ApiFlightWithSplits(arrival, Set())))
    val controller: ForecastAccuracyController = forecastAccuracyController(forecastTotalPax, mlFeedPax, liveFeedPax, mlPredCapPct, flights)
    "get forecast accuracy percentage" in {
      val request = FakeRequest(method = "GET", uri = "", headers = Headers(("X-Auth-Roles", "TEST")), body = AnyContentAsEmpty)

      val result = controller.getForecastAccuracy("2023-01-01").apply(request)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/plain"))
      contentAsString(result) must ===(s"""{"localDate":{"year":2023,"month":1,"day":1},"pax":[["uk.gov.homeoffice.drt.ports.Terminals.T1",{"1":[20],"14":[20],"3":[20],"30":[20],"7":[20]}]]}""".stripMargin)

    }

    "get forecast Accuracy prediction csv" in {
      val request = FakeRequest(method = "GET", uri = "", headers = Headers(("X-Auth-Roles", "TEST")), body = AnyContentAsEmpty)

      val result = controller.forecastAccuracyExport(1, 1).apply(request)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      contentAsString(result) must ===(
        s"""Date,Terminal,Prediction RMSE,Legacy RMSE,Prediction Error,Legacy Error
           |2023-01-31,T1,50.000,20.000,50.000,20.000
           |""".stripMargin)
    }

    "get forecast comparison csv - future date does not include act pax" in {
      val request = FakeRequest(method = "GET", uri = "", headers = Headers(("X-Auth-Roles", "TEST")), body = AnyContentAsEmpty)

      val modelId = "some-model-id"
      val result = controller.forecastModelComparison(modelId, "T1", "2024-02-14", "2024-02-14").apply(request)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      val forecastPcp = forecastArrivalPax - forecastArrivalTransPax
      val fcstCapPct = forecastPcp.toDouble / maxPax * 100
      val mlPax = (mlPredCapPct.toDouble * maxPax / 100).round.toInt
      val liveCapPct = liveArrivalPax.toDouble / maxPax * 100
      contentAsString(result) must ===(
        f"""Date,Act,Port Forecast,DRT Forecast,$modelId,Act Cap%%,Port Forecast Cap%%,DRT Forecast Cap%%,$modelId Cap%%
           |2024-02-14,0,$forecastPcp,0,$mlPax,0.00,$fcstCapPct%.2f,0.00,${mlPredCapPct.toDouble}%.2f
           |""".stripMargin)
    }

    "get forecast comparison csv - historic date does include act pax" in {
      val request = FakeRequest(method = "GET", uri = "", headers = Headers(("X-Auth-Roles", "TEST")), body = AnyContentAsEmpty)

      val modelId = "some-model-id"
      val result = controller.forecastModelComparison(modelId, "T1", "2023-01-01", "2023-01-01").apply(request)

      status(result) must ===(OK)
      contentType(result) must ===(Some("text/csv"))
      val forecastPcp = forecastArrivalPax - forecastArrivalTransPax
      val fcstCapPct = forecastPcp.toDouble / maxPax * 100
      val mlPax = (mlPredCapPct.toDouble * maxPax / 100).round.toInt
      val liveCapPct = liveArrivalPax.toDouble / maxPax * 100
      contentAsString(result) must ===(
        f"""Date,Act,Port Forecast,DRT Forecast,$modelId,Act Cap%%,Port Forecast Cap%%,DRT Forecast Cap%%,$modelId Cap%%
           |2023-01-01,$liveArrivalPax,$forecastPcp,0,$mlPax,$liveCapPct%.2f,$fcstCapPct%.2f,0.00,${mlPredCapPct.toDouble}%.2f
           |""".stripMargin)
    }
  }

  private def forecastAccuracyController(forecastTotalPax: Int, mlFeedPax: Int, liveFeedPax: Int, mlPred: Int, flights: FlightsWithSplits) = {
    val module = new DRTModule() {
      override val isTestEnvironment: Boolean = true
      override val now = () => SDate("2023-02-01T00:00")
    }

    val drtSystemInterface: DrtSystemInterface = new TestDrtSystem(module.airportConfig, module.mockDrtParameters, module.now) {
      override val now: () => SDateLike = () => SDate("2023-02-01T00:00")
      override val forecastPaxNos: (LocalDate, SDateLike) => Future[Map[Terminal, Double]] =
        (_, _) => Future.successful(Map(Terminal("T1") -> forecastTotalPax))

      override val actualPaxNos: LocalDate => Future[Map[Terminal, Double]] =
        _ => Future.successful(Map(Terminal("T1") -> liveFeedPax))

      override val forecastArrivals: (LocalDate, SDateLike) => Future[Map[Terminal, Seq[Arrival]]] = (_, _) => Future.successful(
        Map(T1 -> Seq(ArrivalGenerator.arrival(iata = "BA0001",
          schDt = "2023-10-20T12:25",
          terminal = T1,
          passengerSources = Map(ForecastFeedSource -> Passengers(Some(forecastTotalPax), None),
            MlFeedSource -> Passengers(Some(mlFeedPax), None))))))

      override val actualArrivals: LocalDate => Future[Map[Terminal, Seq[Arrival]]] = _ => Future.successful(
        Map(T1 -> Seq(ArrivalGenerator.arrival(iata = "BA0001",
          schDt = "2023-10-20T12:25",
          terminal = T1,
          passengerSources = Map(LiveFeedSource -> Passengers(Some(liveFeedPax), None),
            ForecastFeedSource -> Passengers(Some(forecastTotalPax), None),
            MlFeedSource -> Passengers(Some(mlFeedPax), None))
        )))
      )

      override val flightModelPersistence: ModelPersistence = MockModelPersistence(mlPred)
      override val flightsRouterActor: ActorRef = system.actorOf(Props(new MockFlightsRouter(flights)))
    }

    new ForecastAccuracyController(Helpers.stubControllerComponents(), drtSystemInterface)
  }

  case class MockModelPersistence(pax: Int) extends ModelPersistence {
    override def getModels(validModelNames: Seq[String]): PredictionModelActor.WithId => Future[PredictionModelActor.Models] =
      _ => Future.successful(Models(Map("some-model-id" -> new ArrivalModelAndFeatures {
        override val model: RegressionModel = RegressionModel(Seq(), 1)
        override val features: FeaturesWithOneToManyValues = FeaturesWithOneToManyValues(List(), IndexedSeq())
        override val targetName: String = "some-model-id"
        override val examplesTrainedOn: Int = 1000
        override val improvementPct: Double = 50
        override val featuresVersion: Int = 1

        override def prediction(arrival: Arrival): Option[Int] = Some(pax)
      })))

    override val persist: (PredictionModelActor.WithId, Int, Any, FeaturesWithOneToManyValues, Int, Double, String) => Future[Done] =
      (_, _, _, _, _, _, _) => Future.successful(Done)
    override val clear: (PredictionModelActor.WithId, String) => Future[Done] =
      (_, _) => Future.successful(Done)
  }

  class MockFlightsRouter(flights: FlightsWithSplits) extends Actor {
    override def receive: Receive = {
      case _ =>
        val date = UtcDate(2023, 1, 1)
        sender() ! Source(List((date, flights)))
    }
  }
}
