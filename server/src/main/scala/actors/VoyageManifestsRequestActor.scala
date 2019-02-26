package actors

import akka.actor.Actor
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{Arrival, ArrivalsDiff}
import manifests.graph.FutureManifests
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

    case FutureManifests(eventualManifests) =>
      eventualManifests
        .map { bestManifests =>
          manifestsResponseQueue.foreach(queue => {
            queue.offer(BestManifestsFeedSuccess(bestManifests.collect { case Success(bm) => bm}, SDate.now())) map {
              case Enqueued => log.info(s"Enqueued ${bestManifests.size} estimated manifests")
              case failure => log.info(s"Failed to enqueue ${bestManifests.size} estimated manifests: $failure")
            }
          })
        }
        .recover { case t =>
          log.info(s"Error getting manifests: ${t.getMessage.take(500)}")
          Failure(t)
        }

    case ArrivalsDiff(arrivals, _) =>
      manifestsRequestQueue.foreach(queue => {
        queue.offer(arrivals.toList) map {
          case Enqueued => log.info(s"Enqueued ${arrivals.size} manifest request arrivals")
          case failure => log.info(s"Failed to enqueue ${arrivals.size} manifest request arrivals: $failure")
        }
      })

    case unexpected =>
      log.warn(s"received unexpected ${unexpected.getClass}")
  }

}

