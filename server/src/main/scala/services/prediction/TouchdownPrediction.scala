package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import actors.persistent.staffing.GetState
import actors.serializers.ModelAndFeatures
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.api.Arrival
import drt.shared.{ArrivalsDiff, MilliTimes, SDateLike, VoyageNumber}
import org.slf4j.LoggerFactory
import services.SDate
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.math.abs

object TouchdownPrediction {
  def modelAndFeatures(now: () => SDateLike)(implicit system: ActorSystem, timeout: Timeout, ec: ExecutionContext): (Terminal, VoyageNumber, PortCode) => Future[Option[ModelAndFeatures]] =
    (terminal, voyageNumber, origin) => {
      val actor = system.actorOf(Props(new TouchdownPredictionActor(now, terminal, voyageNumber, origin)))
      actor
        .ask(GetState).mapTo[Option[ModelAndFeatures]]
        .map { state =>
          actor ! PoisonPill
          state
        }
    }
}

case class TouchdownPrediction(modelAndFeaturesProvider: (Terminal, VoyageNumber, PortCode) => Future[Option[ModelAndFeatures]]) {
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
    modelAndFeaturesProvider(arrival.Terminal, arrival.VoyageNumber, arrival.Origin)
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
