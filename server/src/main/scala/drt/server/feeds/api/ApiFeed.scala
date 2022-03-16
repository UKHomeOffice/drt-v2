package drt.server.feeds.api

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.slf4j.LoggerFactory
import services.SDate

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}


trait ApiFeed {
  def processFilesAfter(since: MillisSinceEpoch): Source[Done, NotUsed]
}

case class ApiFeedImpl(arrivalKeyProvider: ManifestArrivalKeys,
                       manifestProcessor: ManifestProcessor,
                       throttle: FiniteDuration)
                      (implicit ec: ExecutionContext) extends ApiFeed {
  private val log = LoggerFactory.getLogger(getClass)

  override def processFilesAfter(since: MillisSinceEpoch): Source[Done, NotUsed] =
    Source
      .unfoldAsync((since, Iterable[(UniqueArrivalKey, MillisSinceEpoch)]())) { case (lastProcessedAt, lastKeys) =>
        markerAndNextArrivalKeys(lastProcessedAt).map {
          case (nextMarker, newKeys) =>
            if (newKeys.nonEmpty) log.info(s"${newKeys.size} new live manifests available. Next marker: ${SDate(nextMarker).toISOString()}")
            Option((nextMarker, newKeys), (lastProcessedAt, lastKeys))
        }
      }
      .throttle(1, throttle)
      .map { case (_, keys) => keys }
      .mapConcat(identity)
      .mapAsync(1) {
        case (uniqueArrivalKey, processedAt) =>
          manifestProcessor
            .process(uniqueArrivalKey, processedAt)
            .map { res =>
              log.info(s"Manifest successfully processed for $uniqueArrivalKey")
              res
            }
            .recover {
              case t =>
                log.error(s"Failed to process live manifest for $uniqueArrivalKey", t)
                Done
            }
      }

  private def markerAndNextArrivalKeys(since: MillisSinceEpoch): Future[(MillisSinceEpoch, Iterable[(UniqueArrivalKey, MillisSinceEpoch)])] =
    arrivalKeyProvider
      .nextKeys(since)
      .map { keys =>
        val keysToProcess = if (since > 0) keys.filterNot(_._2 == since) else keys
        val nextFetch = keysToProcess.map(_._2).toList.sorted.reverse.headOption.getOrElse(since)
        (nextFetch, keysToProcess)
      }
      .recover {
        case t =>
          log.error(s"Failed to get next arrival keys", t)
          (since, Seq())
      }
}
