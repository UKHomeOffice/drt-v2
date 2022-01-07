package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import actors.persistent.staffing.GetState
import actors.serializers.FeatureType.OneToMany
import actors.serializers.{Features, ModelAndFeatures, RegressionModel}
import akka.actor.{PoisonPill, Props}
import akka.pattern.ask
import controllers.ArrivalGenerator
import drt.shared.{ArrivalsDiff, VoyageNumber}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{T2, Terminal}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class MockTouchdownPredictionActor(terminal: Terminal,
                                   number: VoyageNumber,
                                   origin: PortCode
                                  ) extends TouchdownPredictionActor(() => SDate.now(), terminal, number, origin) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: Features = Features(List(OneToMany(List("dayOfWeek"), "dow"), OneToMany(List("hoursMinutes"), "hhmm")), IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "hhmm_1"))
  state = Option(ModelAndFeatures(model, features))
}

class TouchdownPredictionSpec extends CrunchTestLike {
  val modelAndFeaturesProvider: (Terminal, VoyageNumber, PortCode) => Future[Option[ModelAndFeatures]] =
    (terminal, voyageNumber, origin) => {
      val actor = system.actorOf(Props(new MockTouchdownPredictionActor(terminal, voyageNumber, origin)))
      actor
        .ask(GetState).mapTo[Option[ModelAndFeatures]]
        .map { state =>
          actor ! PoisonPill
          state
        }
    }

  val touchdownPrediction: TouchdownPrediction = TouchdownPrediction(modelAndFeaturesProvider)

  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)
      val maybeUpdatedArrival = touchdownPrediction.maybePredictedTouchdown(arrival)

      val result = Await.result(maybeUpdatedArrival, 1.second)

      result.nonEmpty
    }
  }

  "Given an ArrivalsDiff and an actor containing touchdown prediction models" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)

      val diff = ArrivalsDiff(Seq(arrival), Seq())
      val minutesOffScheduledThreshold = 45

      val arrivals = Await.result(touchdownPrediction.addTouchdownPredictions(diff, minutesOffScheduledThreshold), 1.second).toUpdate.values

      arrivals.exists(a => a.PredictedTouchdown.get !== a.Scheduled)
    }
  }
}
