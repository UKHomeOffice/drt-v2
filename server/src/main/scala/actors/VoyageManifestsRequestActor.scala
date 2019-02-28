package actors

import akka.actor.Actor
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{Arrival, ArrivalsDiff}
import manifests.graph.ManifestTries
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{BestManifestsFeedSuccess, ManifestsFeedResponse}
import services.{ManifestLookupLike, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.{Failure, Success}


class VoyageManifestsRequestActor(portCode: String, manifestLookup: ManifestLookupLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var manifestsRequestQueue: Option[SourceQueueWithComplete[List[Arrival]]] = None
  var manifestsResponseQueue: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None

  override def receive: Receive = {
    case SubscribeRequestQueue(subscriber) =>
      log.info(s"received manifest request subscriber")
      manifestsRequestQueue = Option(subscriber)

    case SubscribeResponseQueue(subscriber) =>
      log.info(s"received manifest response subscriber")
      manifestsResponseQueue = Option(subscriber)

    case ManifestTries(bestManifests) =>
      manifestsResponseQueue.foreach(queue => {
        bestManifests.collect { case Failure(t) => log.error(s"Manifest request failed: $t") }
        val successfulManifests = bestManifests.collect { case Success(bm) => bm }

        queue.offer(BestManifestsFeedSuccess(successfulManifests, SDate.now())).map {
          case Enqueued => Unit
          case failure => log.error(s"Failed to enqueue ${bestManifests.size} estimated manifests: $failure")
        }.recover {
          case t => log.error(s"Enqueuing manifests failure: $t")
        }
      })

    case ArrivalsDiff(arrivals, _) =>
      manifestsRequestQueue.foreach(queue => {
        queue.offer(arrivals.toList).map {
          case Enqueued => log.info(s"Enqueued ${arrivals.size} manifest request arrivals")
          case failure => log.error(s"Failed to enqueue ${arrivals.size} manifest request arrivals: $failure")
        }.recover {
          case t => log.error(s"Enqueuing manifest requests failure: $t")
        }
      })

    case unexpected =>
      log.warn(s"received unexpected ${unexpected.getClass}")
  }

}

