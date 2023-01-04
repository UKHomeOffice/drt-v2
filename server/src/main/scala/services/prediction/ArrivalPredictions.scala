package services.prediction

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.ArrivalsDiff
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.actor.PredictionModelActor.Models
import uk.gov.homeoffice.drt.actor.TerminalDateActor.FlightRoute
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.prediction.{ModelAndFeatures, OffScheduleModelAndFeatures, ToChoxModelAndFeatures}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}
import scala.math.abs


case class ArrivalPredictions(modelAndFeaturesProvider: FlightRoute => Future[Models],
                              modelThresholds: Map[String, Int],
                              minimumImprovementPctThreshold: Int)
                             (implicit ec: ExecutionContext, mat: Materializer) {
  private val log = LoggerFactory.getLogger(getClass)

  val addPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
    diff => {
      log.info(s"Looking up predictions for ${diff.toUpdate.size} arrivals")
      val lastUpdatedThreshold = SDate.now().addDays(-7).millisSinceEpoch
      Source(diff.toUpdate.values.toList)
        .mapAsync(1) { arrival: Arrival =>
          if (recentPredictionTouchdownExists(lastUpdatedThreshold, arrival.Predictions.lastChecked))
            Future.successful(arrival)
          else
            applyPredictions(arrival)
        }
        .runWith(Sink.seq)
        .map { arrivals =>
          diff.copy(toUpdate = diff.toUpdate ++ arrivals.map(a => (a.unique, a)))
        }
    }

  private def recentPredictionTouchdownExists(lastUpdatedThreshold: MillisSinceEpoch,
                                              lastChecked: Long): Boolean = lastChecked > lastUpdatedThreshold

  def applyPredictions(arrival: Arrival): Future[Arrival] = {
    implicit val millisToSDate: MillisSinceEpoch => SDateLike = (millis: MillisSinceEpoch) => SDate(millis)

    modelAndFeaturesProvider(FlightRoute(arrival.Terminal.toString, arrival.VoyageNumber.numeric, arrival.Origin.iata))
      .map { models =>
        models.models.values.foldLeft(arrival) {
          case (arrival, model: OffScheduleModelAndFeatures) =>
            updatePrediction(arrival, model, model.maybeOffScheduleMinutes)
          case (arrival, model: ToChoxModelAndFeatures) =>
            updatePrediction(arrival, model, model.maybeToChoxMinutes)
        }
      }
      .recover {
        case t =>
          log.error(s"Failed to fetch prediction model and features for ${arrival.unique}", t)
          arrival
      }
  }

  private def updatePrediction(arrival: Arrival, model: ModelAndFeatures, predictionProvider: Arrival => Option[Int]): Arrival = {
    val maybeTouchdown: Option[Int] = maybePrediction(arrival, model, predictionProvider)
    val updatedPredictions: Map[String, Int] = maybeTouchdown match {
      case None => arrival.Predictions.predictions
      case Some(update) => arrival.Predictions.predictions.updated(model.targetName, update)
    }
    arrival.copy(Predictions = arrival.Predictions.copy(predictions = updatedPredictions))
  }

  private def maybePrediction(arrival: Arrival,
                              model: ModelAndFeatures,
                              predictor: Arrival => Option[Int]): Option[Int] =
    if (model.improvementPct > minimumImprovementPctThreshold) {
      for {
        valueThreshold <- modelThresholds.get(model.targetName)
        maybeValue <- predictor(arrival)
        maybePrediction <- if (abs(maybeValue) < valueThreshold)
          Option(maybeValue)
        else None
      } yield maybePrediction
    } else None
}
