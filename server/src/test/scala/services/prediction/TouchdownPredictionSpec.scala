package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import actors.persistent.staffing.GetState
import akka.actor.{PoisonPill, Props}
import akka.pattern.ask
import controllers.ArrivalGenerator
import drt.shared.ArrivalsDiff
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{T2, Terminal}
import uk.gov.homeoffice.drt.prediction.Feature.OneToMany
import uk.gov.homeoffice.drt.prediction.{FeaturesWithOneToManyValues, RegressionModel, TouchdownModelAndFeatures}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class MockTouchdownPredictionActor(terminal: Terminal,
                                   number: VoyageNumber,
                                   origin: PortCode
                                  ) extends TouchdownPredictionActor(() => SDate.now(), terminal, number, origin) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: FeaturesWithOneToManyValues = FeaturesWithOneToManyValues(List(OneToMany(List("dayOfWeek"), "dow"), OneToMany(List("hoursMinutes"), "pod")), IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "pod_1"))
  state = Option(TouchdownModelAndFeatures(model, features, examplesTrainedOn = 10, improvementPct = 25))
}

class TouchdownPredictionSpec extends CrunchTestLike {
  val modelAndFeaturesProvider: (Terminal, VoyageNumber, PortCode) => Future[Option[TouchdownModelAndFeatures]] =
    (terminal, voyageNumber, origin) => {
      val actor = system.actorOf(Props(new MockTouchdownPredictionActor(terminal, voyageNumber, origin)))
      actor
        .ask(GetState).mapTo[Option[TouchdownModelAndFeatures]]
        .map { state =>
          actor ! PoisonPill
          state
        }
    }

  val minutesOffScheduledThreshold = 45
  val touchdownPrediction: TouchdownPrediction = TouchdownPrediction(modelAndFeaturesProvider, 45, 10)

  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)
      val maybeUpdatedArrival = touchdownPrediction.maybePredictedTouchdown(arrival)

      val result = Await.result(maybeUpdatedArrival, 1.second)

      result.nonEmpty
    }
  }

//  "Given an ArrivalsDiff and an actor containing touchdown prediction models" >> {
//    "I should be able to update the arrival with an predicted touchdown time" >> {
//      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)
//
//      val diff = ArrivalsDiff(Seq(arrival), Seq())
//
//      val arrivals = Await.result(touchdownPrediction.addTouchdownPredictions(diff), 1.second).toUpdate.values
//
//      arrivals.exists(a => a.PredictedTouchdown.get !== a.Scheduled)
//    }
//  }
}
