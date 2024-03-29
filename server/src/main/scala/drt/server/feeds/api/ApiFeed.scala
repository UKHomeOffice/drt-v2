package drt.server.feeds.api

import akka.stream.scaladsl.Source
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration.{DurationInt, FiniteDuration}
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
            if (newKeys.nonEmpty) log.info(s"${newKeys.size} new live manifests available. Next marker: ${SDate(nextMarker).toISOString}")
            Option((nextMarker, newKeys), (lastProcessedAt, lastKeys))
        }
      }
      .map { case (marker, keys) =>
        if (keys.isEmpty) manifestProcessor.reportNoNewData(marker)
        keys
      }
      .throttle(1, 250.millis)
      .mapConcat(identity)
      .throttle(1, throttle)
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
      .map {
        case (maybeProcessedAt, keysToProcess) =>
        val nextFetch = maybeProcessedAt.getOrElse(since)
        (nextFetch, keysToProcess.map(k => (k, nextFetch)))
      }
      .recover {
        case t =>
          log.error(s"Failed to get next arrival keys", t)
          (since, Seq())
      }
}
