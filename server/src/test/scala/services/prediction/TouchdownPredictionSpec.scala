package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.{ArrivalsDiff, PortState}
import server.feeds.ArrivalsFeedSuccess
import services.SDate
import services.crunch.{CrunchTestLike, TestConfig}
import services.prediction.TouchdownPrediction.MaybeModelAndFeaturesProvider
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.{T2, Terminal}
import uk.gov.homeoffice.drt.prediction.Feature.OneToMany
import uk.gov.homeoffice.drt.prediction.{FeaturesWithOneToManyValues, RegressionModel, TouchdownModelAndFeatures}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class MockTouchdownPredictionActor(now: () => SDateLike,
                                   terminal: Terminal,
                                   number: VoyageNumber,
                                   origin: PortCode
                                  ) extends TouchdownPredictionActor(now, terminal, number, origin) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: FeaturesWithOneToManyValues = FeaturesWithOneToManyValues(List(OneToMany(List("dayOfWeek"), "dow"), OneToMany(List("hoursMinutes"), "pod")), IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "pod_1"))
  state = Option(TouchdownModelAndFeatures(model, features, examplesTrainedOn = 10, improvementPct = 25))
}

class TouchdownPredictionSpec extends CrunchTestLike {
  val modelAndFeaturesProvider: MaybeModelAndFeaturesProvider =
    TouchdownPrediction.modelAndFeaturesProvider(() => SDate.now(), classOf[MockTouchdownPredictionActor])
  val minutesOffScheduledThreshold = 45
  val touchdownPrediction: TouchdownPrediction = TouchdownPrediction(modelAndFeaturesProvider, 45, 10)
  val scheduledStr = "2022-05-01T12:00"

  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)
      val maybePredictedTouchdown = Await.result(touchdownPrediction.maybePredictedTouchdown(arrival), 1.second)

      maybePredictedTouchdown.nonEmpty
    }
  }

  "Given an ArrivalsDiff and an actor containing touchdown prediction models" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)

      val diff = ArrivalsDiff(Seq(arrival), Seq())

      val arrivals = Await.result(touchdownPrediction.addTouchdownPredictions(diff), 1.second).toUpdate.values

      arrivals.exists(a => a.PredictedTouchdown.get !== a.Scheduled)
    }
  }

  "Within CrunchSystem" >> {
    "An Arrivals Graph Stage configured to use predicted times" should {
      "set the correct pcp time given an arrival with a predicted touchdown time" >> {
        val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)

        val crunch = runCrunchGraph(TestConfig(
          now = () => SDate(scheduledStr),
          addTouchdownPredictions = touchdownPrediction.addTouchdownPredictions
        ))

        offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(Iterable(arrival))))

        crunch.portStateTestProbe.fishForMessage(1.seconds, s"looking for a predicted time") {
          case ps: PortState => ps.flights.values.exists(_.apiFlight.PredictedTouchdown.nonEmpty)
        }

        success
      }
    }
  }
}
