package actors

import akka.actor.Actor
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{Arrival, ArrivalsDiff}
import manifests.graph.ManifestTries
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{BestManifestsFeedSuccess, ManifestsFeedResponse}
import services.{ManifestLookupLike, OfferHandler, SDate}

import scala.Option
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
        val successfulManifests = bestManifests.collect { case Some(bm) => bm }
        val bestManifestsResult = BestManifestsFeedSuccess(successfulManifests, SDate.now())

        OfferHandler.offerWithRetries(queue, bestManifestsResult, 10)
      })

    case ArrivalsDiff(arrivals, _) => manifestsRequestQueue.foreach(queue => OfferHandler.offerWithRetries(queue, arrivals.toList, 10))

    case unexpected => log.warn(s"received unexpected ${unexpected.getClass}")
  }

}

