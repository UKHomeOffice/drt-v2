package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import actors.persistent.staffing.GetState
import akka.actor.{ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.ArrivalsDiff
import org.slf4j.LoggerFactory
import services.SDate
import services.prediction.TouchdownPrediction.MaybeModelAndFeaturesProvider
import uk.gov.homeoffice.drt.arrivals.{Arrival, VoyageNumber}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.prediction.TouchdownModelAndFeatures
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContext, Future}
import scala.math.abs

object TouchdownPrediction {
  type MaybeModelAndFeaturesProvider = (Terminal, VoyageNumber, PortCode) => Future[Option[TouchdownModelAndFeatures]]

  def modelAndFeaturesProvider(now: () => SDateLike)
                              (implicit system: ActorSystem, timeout: Timeout, ec: ExecutionContext): MaybeModelAndFeaturesProvider =
    (terminal, voyageNumber, origin) => {
      val actor = system.actorOf(Props(new TouchdownPredictionActor(now, terminal, voyageNumber, origin)))
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
                               minimumImprovementPctThreshold: Int)
                              (implicit ec: ExecutionContext, mat: Materializer) {
  private val log = LoggerFactory.getLogger(getClass)

  def addTouchdownPredictions(diff: ArrivalsDiff): Future[ArrivalsDiff] =
    Source(diff.toUpdate.values.toList)
      .mapAsync(1) { arrival: Arrival =>
        maybePredictedTouchdown(arrival).map {
          case None =>
            log.info(s"No prediction model found for ${arrival.flightCodeString} ${SDate(arrival.Scheduled).toISOString()}")
            arrival.copy(PredictedTouchdown = None)
          case Some(prediction) =>
            val minutesOffScheduled = prediction - arrival.Scheduled
            val absMinutesOffScheduled = abs(minutesOffScheduled).millis.toMinutes
            if (absMinutesOffScheduled <= minutesOffScheduledThreshold) {
              log.info(s"Adding $minutesOffScheduled to ${arrival.flightCodeString} ${SDate(arrival.Scheduled).toISOString()}")
              arrival.copy(PredictedTouchdown = Option(prediction))
            } else {
              log.warn(s"Predicted touchdown is $absMinutesOffScheduled minutes off scheduled time. Threshold is $minutesOffScheduledThreshold")
              arrival.copy(PredictedTouchdown = None)
            }
        }
      }
      .runWith(Sink.seq)
      .map { arrivals =>
        diff.copy(toUpdate = diff.toUpdate ++ arrivals.map(a => (a.unique, a)))
      }

  def maybePredictedTouchdown(arrival: Arrival): Future[Option[Long]] =
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
