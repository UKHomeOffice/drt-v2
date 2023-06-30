package services.prediction

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.server.feeds.ArrivalsFeedSuccess
import drt.shared.FlightsApi.Flights
import drt.shared.{ArrivalsDiff, PortState}
import services.crunch.{CrunchTestLike, TestConfig}
import uk.gov.homeoffice.drt.actor.PredictionModelActor
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{TerminalCarrier, TerminalCarrierOrigin, TerminalFlightNumberOrigin, TerminalOrigin, WithId}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2}
import uk.gov.homeoffice.drt.prediction._
import uk.gov.homeoffice.drt.prediction.arrival.FeatureColumns.{DayOfWeek, PartOfDay}
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
                                ) extends Persistence {
  override val now: () => SDateLike = () => SDate.now()
  override val modelCategory: ModelCategory = FlightCategory
  override val actorProvider: (ModelCategory, WithId) => ActorRef =
    (modelCategory, identifier) => system.actorOf(Props(new MockPredictionModelActor(() => SDate.now(), modelCategory, identifier)))
}


class ArrivalPredictionsSpec extends CrunchTestLike {
  val minutesOffScheduledThreshold = 45
  private val modelKeysForArrival: Arrival => Iterable[WithId] = (a: Arrival) => Iterable(
    TerminalFlightNumberOrigin(a.Terminal.toString, a.VoyageNumber.numeric, a.Origin.iata),
    TerminalCarrierOrigin(a.Terminal.toString, a.CarrierCode.code, a.Origin.iata),
  )

  val arrivalPredictions: ArrivalPredictions = ArrivalPredictions(
    modelKeysForArrival,
    MockFlightPersistence().getModels(Seq(OffScheduleModelAndFeatures.targetName)),
    Map(OffScheduleModelAndFeatures.targetName -> minutesOffScheduledThreshold),
    10)
  val scheduledStr = "2022-05-01T12:00"

  "arrivalsByKey should" >> {
    "take some arrivals and a function to create their keys and return a list of keys to arrivals" >> {
      val jfkT1 = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T1)
      val bhxT1 = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("BHX"), terminal = T1)
      val jfkT2 = ArrivalGenerator.arrival("BA0002", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)
      val bhxT2 = ArrivalGenerator.arrival("BA0003", schDt = scheduledStr, origin = PortCode("BHX"), terminal = T2)
      val arrivals = List(jfkT1, bhxT1, jfkT2, bhxT2)
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
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = scheduledStr, origin = PortCode("JFK"), terminal = T2)
      val keysWithArrival = modelKeysForArrival(arrival).map(k => (k, List(arrival.unique))).toList
      val arrivalsMap = List(arrival).map(a => (a.unique, a)).toMap
      val maybePredictedTouchdown = Await.result(arrivalPredictions.applyPredictionsByKey(arrivalsMap, keysWithArrival), 1.second)

      maybePredictedTouchdown.head.predictedTouchdown.nonEmpty
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
