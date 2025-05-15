package services.prediction

import controllers.ArrivalGenerator
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.util.Timeout
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.actor.PredictionModelActor._
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, Predictions}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.prediction._
import uk.gov.homeoffice.drt.prediction.arrival.OffScheduleModelAndFeatures
import uk.gov.homeoffice.drt.prediction.arrival.features.FeatureColumnsV1.{DayOfWeek, PartOfDay}
import uk.gov.homeoffice.drt.prediction.category.FlightCategory
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext}

class MockPredictionModelActor(now: () => SDateLike,
                               category: ModelCategory,
                               identifier: WithId,
                              ) extends PredictionModelActor(now, category, identifier, None) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: FeaturesWithOneToManyValues = FeaturesWithOneToManyValues(
    List(DayOfWeek(), PartOfDay()),
    IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "pod_1")
  )
  state = Map(OffScheduleModelAndFeatures.targetName -> OffScheduleModelAndFeatures(model, features, examplesTrainedOn = 10, improvementPct = 25))
}

case class MockFlightPersistence()
                                (implicit
                                 val ec: ExecutionContext,
                                 val timeout: Timeout,
                                 val system: ActorSystem
                                ) extends ActorModelPersistence {
  override val modelCategory: ModelCategory = FlightCategory
  override val actorProvider: (ModelCategory, WithId, Option[Long]) => ActorRef =
    (modelCategory, identifier, _) => system.actorOf(Props(new MockPredictionModelActor(() => SDate.now(), modelCategory, identifier)))
}


class ArrivalPredictionsSpec extends CrunchTestLike {
  val minutesOffScheduledThreshold = 45
  private val modelKeysForArrival: Arrival => Iterable[WithId] = (a: Arrival) => Iterable(
    TerminalFlightNumberOrigin(a.Terminal.toString, a.VoyageNumber.numeric, a.Origin.iata),
    TerminalCarrierOrigin(a.Terminal.toString, a.CarrierCode.code, a.Origin.iata),
  )

  private val myNow: SDateLike = SDate.now()

  val arrivalPredictions: ArrivalPredictions = ArrivalPredictions(
    modelKeys = modelKeysForArrival,
    getModels = MockFlightPersistence().getModels(Seq(OffScheduleModelAndFeatures.targetName), None),
    modelThresholds = Map(OffScheduleModelAndFeatures.targetName -> minutesOffScheduledThreshold),
    minimumImprovementPctThreshold = 10,
    now = () => myNow,
    staleFrom = 24.hours,
  )

  val scheduledStr = myNow.toISOString

  "arrivalsByKey should" >> {
    "take some arrivals and a function to create their keys and return a list of keys to arrivals" >> {
      val jfkT1 = ArrivalGenerator.live("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T1)
      val bhxT1 = ArrivalGenerator.live("BA0001", schDt = scheduledStr, origin = PortCode("BHX"), terminal = T1)
      val jfkT2 = ArrivalGenerator.live("BA0002", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)
      val bhxT2 = ArrivalGenerator.live("BA0003", schDt = scheduledStr, origin = PortCode("BHX"), terminal = T2)
      val arrivals = List(jfkT1, bhxT1, jfkT2, bhxT2).map(_.toArrival(LiveFeedSource))
      val modelKeys: Arrival => Iterable[WithId] = arrival =>
        List(
          TerminalOrigin(arrival.Terminal.toString, arrival.Origin.iata),
          TerminalCarrier(arrival.Terminal.toString, arrival.CarrierCode.code),
        )

      ArrivalPredictions.arrivalsByKey(arrivals, modelKeys).toSet === Set(
        (TerminalOrigin("T1", "JFK"), List(jfkT1.unique)),
        (TerminalOrigin("T2", "JFK"), List(jfkT2.unique)),
        (TerminalOrigin("T1", "BHX"), List(bhxT1.unique)),
        (TerminalOrigin("T2", "BHX"), List(bhxT2.unique)),
        (TerminalCarrier("T1", "BA"), List(jfkT1.unique, bhxT1.unique)),
        (TerminalCarrier("T2", "BA"), List(jfkT2.unique, bhxT2.unique)),
      )
    }
  }

  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.live("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2).toArrival(LiveFeedSource)
      val keysWithArrival = modelKeysForArrival(arrival).map(k => (k, List(arrival.unique))).toList
      val arrivalsMap = List(arrival).map(a => (a.unique, a)).toMap
      val maybePredictedTouchdown = Await.result(arrivalPredictions.applyPredictionsByKey(arrivalsMap, keysWithArrival), 5.second)

      maybePredictedTouchdown.head.predictedTouchdown.nonEmpty === true
    }
  }

  "Given an ArrivalsDiff and an actor containing touchdown prediction models" >> {
    val arrival = ArrivalGenerator.live("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2).toArrival(LiveFeedSource)
    val oldPredictionValue = -4
    val newPredictionValue = 0

    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.live("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2).toArrival(LiveFeedSource)

      val diff = ArrivalsDiff(Seq(arrival), Seq())

      val arrivals = Await.result(arrivalPredictions.addPredictions(diff), 1.second).toUpdate.values

      arrivals.exists(a => a.predictedTouchdown.nonEmpty)
    }

    "lastUpdated should only be set if the new predicted value is different from the existing one" >> {
      val currentPredictions = Predictions(
        myNow.addDays(-2).millisSinceEpoch,
        Map(OffScheduleModelAndFeatures.targetName -> newPredictionValue)
      )

      val diff = ArrivalsDiff(Seq(arrival.copy(Predictions = currentPredictions)), Seq())

      val arrivals = Await.result(arrivalPredictions.addPredictions(diff), 1.second).toUpdate.values

      arrivals.exists(a => a.Predictions.lastUpdated !== currentPredictions.lastUpdated)
    }

    "predictions should not be updated if it's less than the threshold time since the last update" >> {
      val currentPredictions = Predictions(
        myNow.addHours(-2).millisSinceEpoch,
        Map(OffScheduleModelAndFeatures.targetName -> oldPredictionValue)
      )

      val diff = ArrivalsDiff(Seq(arrival.copy(Predictions = currentPredictions)), Seq())

      val arrivals = Await.result(arrivalPredictions.addPredictions(diff), 1.second).toUpdate.values

      arrivals.exists(a => a.Predictions == currentPredictions)
    }

    "predictions should be updated if it's more than the threshold time since the last update" >> {
      val currentPredictions = Predictions(
        myNow.addDays(-2).millisSinceEpoch,
        Map(OffScheduleModelAndFeatures.targetName -> -100)
      )

      val diff = ArrivalsDiff(Seq(arrival.copy(Predictions = currentPredictions)), Seq())

      val arrivals = Await.result(arrivalPredictions.addPredictions(diff), 1.second).toUpdate.values

      arrivals.exists(a => a.Predictions.lastUpdated != currentPredictions.lastUpdated)
    }
  }
}
