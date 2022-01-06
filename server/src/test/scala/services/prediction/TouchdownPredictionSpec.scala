package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import actors.persistent.staffing.GetState
import actors.serializers.FeatureType.OneToMany
import actors.serializers.{Features, ModelAndFeatures, RegressionModel}
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import controllers.ArrivalGenerator
import drt.shared.api.Arrival
import drt.shared.{ArrivalsDiff, MilliTimes, SDateLike}
import org.slf4j.LoggerFactory
import services.SDate
import services.crunch.CrunchTestLike
import services.prediction.TouchdownPredictions.{addTouchdownPredictions, maybePredictedTouchdown}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T2

import scala.concurrent.duration.{DurationInt, DurationLong}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.math.abs

class MockTouchdownPredictionActor(now: () => SDateLike,
                                   terminal: String,
                                   number: Int,
                                   origin: String
                                  ) extends TouchdownPredictionActor(now, terminal, number, origin) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: Features = Features(List(OneToMany(List("dayOfWeek"), "dow"), OneToMany(List("hoursMinutes"), "hhmm")), IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "hhmm_1"))
  state = Option(ModelAndFeatures(model, features))
}

object TouchdownPredictions {
  private val log = LoggerFactory.getLogger(getClass)

  def addTouchdownPredictions(diff: ArrivalsDiff, minutesOffScheduledThreshold: Int)
                             (implicit ec: ExecutionContext, mat: Materializer, system: ActorSystem, timeout: Timeout): Future[ArrivalsDiff] =
    Source(diff.toUpdate.values.toList)
      .mapAsync(1) { arrival: Arrival =>
        maybePredictedTouchdown(arrival).map {
          case Some(prediction) =>
            val minutesOffScheduled = abs(prediction - arrival.Scheduled).millis.toMinutes
            if (minutesOffScheduled <= minutesOffScheduledThreshold)
              Option(arrival.copy(PredictedTouchdown = Option(prediction)))
            else {
              log.warn(s"Predicted touchdown is $minutesOffScheduled minutes off scheduled time. Threshold is $minutesOffScheduledThreshold")
              None
            }
        }
      }
      .collect { case Some(updated) => updated }
      .runWith(Sink.seq)
      .map { arrivals =>
        diff.copy(toUpdate = diff.toUpdate ++ arrivals.map(a => (a.unique, a)))
      }

  def maybePredictedTouchdown(arrival: Arrival)
                             (implicit system: ActorSystem, timeout: Timeout, ec: ExecutionContext): Future[Option[Long]] = {
    val actor = system.actorOf(Props(new MockTouchdownPredictionActor(() => SDate.now(), T2.toString, arrival.VoyageNumber.numeric, arrival.Origin.iata)))
    actor
      .ask(GetState).mapTo[Option[ModelAndFeatures]]
      .map { state =>
        actor ! PoisonPill
        state
      }
      .collect {
        case Some(modelAndFeatures) =>
          val dow = s"dow_${SDate(arrival.Scheduled).getDayOfWeek()}"
          val hhmm = s"hhmm_${SDate(arrival.Scheduled).getHours() / 12}"
          val dowIdx = modelAndFeatures.features.oneToManyValues.indexOf(dow)
          val hhmmIdx = modelAndFeatures.features.oneToManyValues.indexOf(hhmm)
          for {
            dowCo <- modelAndFeatures.model.coefficients.toIndexedSeq.lift(dowIdx)
            hhmmCo <- modelAndFeatures.model.coefficients.toIndexedSeq.lift(hhmmIdx)
          } yield {
            val offScheduled = (modelAndFeatures.model.intercept + dowCo + hhmmCo).toInt
            arrival.Scheduled + (offScheduled * MilliTimes.oneMinuteMillis)
          }
      }
      .recover {
        case t =>
          log.error(s"Failed to fetch prediction model and features for ${arrival.unique}", t)
          None
      }
  }
}

class TouchdownPredictionSpec extends CrunchTestLike {
  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)
      val maybeUpdatedArrival = maybePredictedTouchdown(arrival)

      val result = Await.result(maybeUpdatedArrival, 1.second)

      result.nonEmpty
    }
  }

  "Given an ArrivalsDiff and an actor containing touchdown prediction models" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)

      val diff = ArrivalsDiff(Seq(arrival), Seq())
      val minutesOffScheduledThreshold = 45

      val arrivals = Await.result(addTouchdownPredictions(diff, minutesOffScheduledThreshold), 1.second).toUpdate.values

      arrivals.exists(a => a.PredictedTouchdown.get !== a.Scheduled)
    }
  }
}
