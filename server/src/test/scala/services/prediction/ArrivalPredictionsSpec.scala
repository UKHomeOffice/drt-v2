package services.prediction

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.FlightsApi.Flights
import drt.shared.{ArrivalsDiff, PortState}
import services.crunch.{CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{TerminalCarrierOrigin, TerminalFlightNumberOrigin, WithId}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T2
import uk.gov.homeoffice.drt.prediction.Feature.OneToMany
import uk.gov.homeoffice.drt.prediction._
import uk.gov.homeoffice.drt.prediction.arrival.OffScheduleModelAndFeatures
import uk.gov.homeoffice.drt.prediction.category.FlightCategory
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

class MockPredictionModelActor(now: () => SDateLike,
                               category: ModelCategory,
                               identifier: WithId,
                              ) extends PredictionModelActor(now, category, identifier) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: FeaturesWithOneToManyValues = FeaturesWithOneToManyValues(List(OneToMany(List("dayOfWeek"), "dow"), OneToMany(List("hoursMinutes"), "pod")), IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "pod_1"))
  state = Map(OffScheduleModelAndFeatures.targetName -> OffScheduleModelAndFeatures(model, features, examplesTrainedOn = 10, improvementPct = 25))
}

case class MockFlightPersistence()
                                (implicit
                                 val ec: ExecutionContext,
                                 val timeout: Timeout,
                                 val system: ActorSystem
                                ) extends Persistence {
  override val now: () => SDateLike = () => SDate.now()
  override val modelCategory: ModelCategory = FlightCategory
  override val actorProvider: (ModelCategory, WithId) => ActorRef =
    (modelCategory, identifier) => system.actorOf(Props(new MockPredictionModelActor(() => SDate.now(), modelCategory, identifier)))
}


class ArrivalPredictionsSpec extends CrunchTestLike {
  val minutesOffScheduledThreshold = 45
  val arrivalPredictions: ArrivalPredictions = ArrivalPredictions(
    (a: Arrival) => Iterable(
      TerminalFlightNumberOrigin(a.Terminal.toString, a.VoyageNumber.numeric, a.Origin.iata),
      TerminalCarrierOrigin(a.Terminal.toString, a.CarrierCode.code, a.Origin.iata),
    ),
    MockFlightPersistence().getModels,
    Map(OffScheduleModelAndFeatures.targetName -> minutesOffScheduledThreshold),
    10)
  val scheduledStr = "2022-05-01T12:00"

  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)
      val maybePredictedTouchdown = Await.result(arrivalPredictions.applyPredictions(arrival), 1.second)

      maybePredictedTouchdown.predictedTouchdown.nonEmpty
    }
  }

  "Given an ArrivalsDiff and an actor containing touchdown prediction models" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)

      val diff = ArrivalsDiff(Seq(arrival), Seq())

      val arrivals = Await.result(arrivalPredictions.addPredictions(diff), 1.second).toUpdate.values

      arrivals.exists(a => a.predictedTouchdown.get !== a.Scheduled)
    }
  }

  "Within CrunchSystem" >> {
    "An Arrivals Graph Stage configured to use predicted times" should {
      "set the correct pcp time given an arrival with a predicted touchdown time" >> {
        val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledStr),
          addTouchdownPredictions = arrivalPredictions.addPredictions
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival))))

        crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for a predicted time") {
          case ps: PortState => ps.flights.values.exists(_.apiFlight.predictedTouchdown.nonEmpty)
        }

        success
      }
    }
  }
}
