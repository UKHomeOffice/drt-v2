package services.prediction

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.ArrivalsDiff
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import services.prediction.ArrivalPredictions.arrivalsByKey
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{Models, WithId}
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.prediction.ModelAndFeatures
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}
import scala.math.abs


object ArrivalPredictions {
  def arrivalsByKey(arrivals: Iterable[Arrival], keys: Arrival => Iterable[WithId]): List[(WithId, Iterable[UniqueArrival])] = {
    arrivals
      .flatMap(a => keys(a).map(k => (k, a.unique)))
      .groupBy(_._1)
      .map(kv => (kv._1, kv._2.map(_._2)))
      .toList
  }
}

case class ArrivalPredictions(modelKeys: Arrival => Iterable[WithId],
                              modelAndFeaturesProvider: WithId => Future[Models],
                              modelThresholds: Map[String, Int],
                              minimumImprovementPctThreshold: Int)
                             (implicit ec: ExecutionContext, mat: Materializer) {
  private val log = LoggerFactory.getLogger(getClass)

  val addPredictions: ArrivalsDiff => Future[ArrivalsDiff] =
    diff => {
      val byKey = arrivalsByKey(diff.toUpdate.values, modelKeys)
      applyPredictionsByKey(diff.toUpdate, byKey)
        .map { arrivals =>
          diff.copy(toUpdate = diff.toUpdate ++ arrivals.map(a => (a.unique, a)))
        }
    }

  def applyPredictionsByKey(arrivals: Map[UniqueArrival, Arrival], idsToArrivalKey: List[(WithId, Iterable[UniqueArrival])]): Future[Iterable[Arrival]] = {
    val lastUpdatedThreshold = SDate.now().addDays(-7).millisSinceEpoch

    Source(idsToArrivalKey)
      .foldAsync(arrivals) {
        case (arrivalsAcc, (key, uniqueArrivals)) =>
          if (oldValuesExist(lastUpdatedThreshold, arrivalsAcc, uniqueArrivals))
            findAndApplyForKey(key, uniqueArrivals.map(arrivalsAcc(_)))
              .map(updates => updates.foldLeft(arrivalsAcc)((acc, a) => acc.updated(a.unique, a)))
          else
            Future.successful(arrivalsAcc)
      }
      .map { things => things.values }
      .runWith(Sink.head)
  }

  private def oldValuesExist(lastUpdatedThreshold: MillisSinceEpoch,
                             arrivalsAcc: Map[UniqueArrival, Arrival],
                             uniqueArrivals: Iterable[UniqueArrival]): Boolean =
    uniqueArrivals.map(arrivalsAcc(_)).exists(_.Predictions.lastChecked < lastUpdatedThreshold)

  private def findAndApplyForKey(key: WithId, arrivals: Iterable[Arrival]): Future[Iterable[Arrival]] =
    modelAndFeaturesProvider(key).map { models =>
      arrivals.map { arrival =>
        models.models.values.foldLeft(arrival) {
          case (arrival, model: ArrivalModelAndFeatures) =>
            updatePrediction(arrival, model, model.prediction)
          case (_, unknownModel) =>
            log.error(s"Unknown model type ${unknownModel.getClass}")
            arrival
        }
      }
    }

  private def updatePrediction(arrival: Arrival, model: ModelAndFeatures, predictionProvider: Arrival => Option[Int]): Arrival = {
    val updatedPredictions: Map[String, Int] = maybePrediction(arrival, model, predictionProvider) match {
      case None => arrival.Predictions.predictions.removed(model.targetName)
      case Some(update) => arrival.Predictions.predictions.updated(model.targetName, update)
    }
    arrival.copy(Predictions = arrival.Predictions.copy(predictions = updatedPredictions, lastChecked = SDate.now().millisSinceEpoch))
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
