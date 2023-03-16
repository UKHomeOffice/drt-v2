package services.prediction

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.ArrivalsDiff
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{Models, WithId}
import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.prediction.ModelAndFeatures
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}
import scala.math.abs


case class ArrivalPredictions(modelKeys: Arrival => Iterable[WithId],
                              modelAndFeaturesProvider: WithId => Future[Models],
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
          if (recentPredictionExists(lastUpdatedThreshold, arrival.Predictions.lastChecked))
            Future.successful(arrival)
          else
            applyPredictions(arrival)
        }
        .runWith(Sink.seq)
        .map { arrivals =>
          diff.copy(toUpdate = diff.toUpdate ++ arrivals.map(a => (a.unique, a)))
        }
    }

  private def recentPredictionExists(lastUpdatedThreshold: MillisSinceEpoch, lastChecked: Long): Boolean =
    false //lastChecked > lastUpdatedThreshold

  def applyPredictions(arrival: Arrival): Future[Arrival] = {
    implicit val millisToSDate: MillisSinceEpoch => SDateLike = (millis: MillisSinceEpoch) => SDate(millis)

    Source(modelKeys(arrival).toList)
      .foldAsync(arrival) {
        case (accArrival, key) =>
          println(s"looking up model for $key")
          modelAndFeaturesProvider(key)
            .map { models =>

              models.models.values.foldLeft(accArrival) {
                case (arrival, model: ArrivalModelAndFeatures) =>
                  updatePrediction(arrival, model, model.prediction)
                case (_, unknownModel) =>
                  log.error(s"Unknown model type ${unknownModel.getClass}")
                  accArrival
              }
            }
            .recover {
              case t =>
                log.error(s"Failed to fetch prediction model and features for ${arrival.unique}", t)
                accArrival
            }
      }
      .runWith(Sink.head)
  }

  private def updatePrediction(arrival: Arrival, model: ModelAndFeatures, predictionProvider: Arrival => Option[Int]): Arrival = {
    val updatedPredictions: Map[String, Int] = maybePrediction(arrival, model, predictionProvider) match {
      case None =>
        arrival.Predictions.predictions.removed(model.targetName)
      case Some(update) =>
        arrival.Predictions.predictions.updated(model.targetName, update)
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
        else {
          None
        }
      } yield maybePrediction
    } else {
      None
    }
}
