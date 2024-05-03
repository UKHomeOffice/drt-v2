package drt.server.feeds.api

import akka.{Done, NotUsed}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.MillisSinceEpoch
import manifests.UniqueArrivalKey
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}


trait ApiFeed {
  def startProcessingFrom(since: MillisSinceEpoch): Source[Done, NotUsed]
}

case class ApiFeedImpl(arrivalKeyProvider: ManifestArrivalKeys,
                       manifestProcessor: ManifestProcessor,
                       throttleDuration: FiniteDuration)
                      (implicit ec: ExecutionContext) extends ApiFeed {
  private val log = LoggerFactory.getLogger(getClass)

  override def startProcessingFrom(since: MillisSinceEpoch): Source[Done, NotUsed] =
    Source
      .unfoldAsync((since, Iterable[(UniqueArrivalKey, MillisSinceEpoch)]())) { case (lastProcessedAt, lastKeys) =>
        markerAndNextArrivalKeys(lastProcessedAt).map {
          case (nextMarker, newKeys) =>
            if (newKeys.nonEmpty) log.info(s"${newKeys.size} new live manifests available. Next marker: ${SDate(nextMarker).toISOString}")
            Option(((nextMarker, newKeys), (lastProcessedAt, lastKeys)))
        }
      }
      .throttle(1, throttleDuration)
      .map { case (marker, keys) =>
        if (keys.isEmpty) manifestProcessor.reportNoNewData(marker)
        keys
      }
      .mapAsync(1) {
        case keysWithProcessedAt if keysWithProcessedAt.nonEmpty =>
          val keys = keysWithProcessedAt.map(_._1)
          val processedAt = keysWithProcessedAt.map(_._2).max
          manifestProcessor
            .process(keys.toSeq, processedAt)
            .map { res =>
              log.info(s"Successfully processed ${keys.size} manifests")
              res
            }
            .recover {
              case t =>
                log.error(s"Failed to process ${keys.size} manifests", t)
                Done
            }

        case _ =>
          Future.successful(Done)
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
