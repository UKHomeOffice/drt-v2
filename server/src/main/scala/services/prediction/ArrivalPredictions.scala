package services.prediction

import org.apache.pekko.stream.Materializer
import org.apache.pekko.stream.scaladsl.{Sink, Source}
import org.slf4j.LoggerFactory
import services.prediction.ArrivalPredictions.arrivalsByKey
import uk.gov.homeoffice.drt.actor.PredictionModelActor.{Models, WithId}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.prediction.arrival.ArrivalModelAndFeatures
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}


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
                              getModels: WithId => Future[Models],
                              modelThresholds: Map[String, Int],
                              minimumImprovementPctThreshold: Int,
                              now: () => SDateLike,
                              staleFrom: FiniteDuration,
                             )
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

  def applyPredictionsByKey(arrivals: Map[UniqueArrival, Arrival], idsToArrivalKey: List[(WithId, Iterable[UniqueArrival])]): Future[Iterable[Arrival]] =
    Source(idsToArrivalKey)
      .foldAsync(arrivals) {
        case (arrivalsAcc, (key, uniqueArrivals)) =>
          findAndApplyForKey(key, uniqueArrivals.map(arrivalsAcc(_)))
            .map(updates => updates.foldLeft(arrivalsAcc)((acc, a) => acc.updated(a.unique, a)))
      }
      .map { things => things.values }
      .runWith(Sink.head)

  private def findAndApplyForKey(modelKey: WithId, arrivals: Iterable[Arrival]): Future[Iterable[Arrival]] =
    getModels(modelKey).map { models =>
      arrivals
        .filter { arrival =>
          val sinceLastCheck = (now().millisSinceEpoch - arrival.Predictions.lastUpdated).millis
          sinceLastCheck >= staleFrom
        }
        .map { arrival =>
          models.models.values.foldLeft(arrival) {
            case (arrival, model: ArrivalModelAndFeatures) =>
              model.updatePrediction(arrival, minimumImprovementPctThreshold, modelThresholds.get(model.targetName), now())
            case (arrival, unknownModel) =>
              log.error(s"Unknown model type ${unknownModel.getClass}")
              arrival
          }
        }
    }
}
