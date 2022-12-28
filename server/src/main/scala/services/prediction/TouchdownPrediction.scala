package services.prediction

import actors.persistent.staffing.GetState
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.ArrivalsDiff
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import services.prediction.TouchdownPrediction.MaybeModelAndFeaturesProvider
import uk.gov.homeoffice.drt.arrivals.{Arrival, Prediction, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.prediction.TouchdownModelAndFeatures
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.math.abs

object TouchdownPrediction {
  type MaybeModelAndFeaturesProvider = (Terminal, VoyageNumber, PortCode) => Future[Option[TouchdownModelAndFeatures]]

  def modelAndFeaturesProvider[T](now: () => SDateLike, clazz: Class[T])
                              (implicit system: ActorSystem, timeout: Timeout, ec: ExecutionContext): MaybeModelAndFeaturesProvider =
    (terminal, voyageNumber, origin) => {
      val actor = system.actorOf(Props(clazz, now, terminal, voyageNumber, origin))
      actor
        .ask(GetState).mapTo[Option[TouchdownModelAndFeatures]]
        .map { state =>
          actor ! PoisonPill
          state
        }
    }
}

case class TouchdownPrediction(modelAndFeaturesProvider: MaybeModelAndFeaturesProvider,
                               minutesOffScheduledThreshold: Int,
                               minimumImprovementPctThreshold: Int,
                              )
                              (implicit ec: ExecutionContext, mat: Materializer) {
  private val log = LoggerFactory.getLogger(getClass)

  val addTouchdownPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
    diff => {
      log.info(s"Looking up predictions for ${diff.toUpdate.size} arrivals")
      val lastUpdatedThreshold = SDate.now().addDays(-7).millisSinceEpoch
      Source(diff.toUpdate.values.toList)
        .mapAsync(1) { arrival: Arrival =>
          if (recentPredictionExists(lastUpdatedThreshold, arrival)) Future.successful(arrival)
          else {
            maybePredictedTouchdown(arrival).map {
              case None =>
                arrival.copy(PredictedTouchdown = None)
              case Some(prediction) =>
                val minutesOffScheduled = prediction - arrival.Scheduled
                val absMinutesOffScheduled = abs(minutesOffScheduled).millis.toMinutes
                if (absMinutesOffScheduled <= minutesOffScheduledThreshold) {
                  arrival.copy(PredictedTouchdown = Option(Prediction(SDate.now().millisSinceEpoch, prediction)))
                } else {
                  log.warn(s"Predicted touchdown is $absMinutesOffScheduled minutes off scheduled time. Threshold is $minutesOffScheduledThreshold")
                  arrival.copy(PredictedTouchdown = None)
                }
            }
          }
        }
        .runWith(Sink.seq)
        .map { arrivals =>
          diff.copy(toUpdate = diff.toUpdate ++ arrivals.map(a => (a.unique, a)))
        }
    }

  private def recentPredictionExists(lastUpdatedThreshold: MillisSinceEpoch, arrival: Arrival): Boolean =
    arrival.PredictedTouchdown.exists(_.updatedAt > lastUpdatedThreshold)

  def maybePredictedTouchdown(arrival: Arrival): Future[Option[Long]] = {
    implicit val millisToSDate: MillisSinceEpoch => SDateLike = (millis: MillisSinceEpoch) => SDate(millis)

    modelAndFeaturesProvider(arrival.Terminal, arrival.VoyageNumber, arrival.Origin)
      .map {
        case Some(maf) if maf.improvementPct > minimumImprovementPctThreshold => maf.maybePrediction(arrival)
        case _ => None
      }
      .recover {
        case t =>
          log.error(s"Failed to fetch prediction model and features for ${arrival.unique}", t)
          None
      }
  }
}
