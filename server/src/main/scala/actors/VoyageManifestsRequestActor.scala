package actors

import actors.AckingReceiver.{Ack, StreamInitialized}
import akka.actor.{Actor, ActorRef, Scheduler}
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{Arrival, ArrivalsDiff}
import manifests.ManifestLookupLike
import manifests.graph.ManifestTries
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{BestManifestsFeedSuccess, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.graphstages.DqManifests
import services.{OfferHandler, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps
import scala.util.Success


object AckingReceiver {

  case object Ack

  case object StreamInitialized

  case object StreamCompleted

  final case class StreamFailure(ex: Throwable)

}

class VoyageManifestsRequestActor(portCode: String, manifestLookup: ManifestLookupLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var manifestsRequestQueue: Option[SourceQueueWithComplete[List[Arrival]]] = None
  var manifestsResponseQueue: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None

//  var liveManifestsBuffer: Option[ManifestsFeedSuccess] = None

  def senderRef(): ActorRef = sender()

  implicit val scheduler: Scheduler = this.context.system.scheduler

  override def receive: Receive = {
    case StreamInitialized =>
      log.info("Stream initialized!")
      sender() ! Ack

    case SubscribeRequestQueue(subscriber) =>
      log.info(s"received manifest request subscriber")
      manifestsRequestQueue = Option(subscriber)

    case SubscribeResponseQueue(subscriber) =>
      log.info(s"received manifest response subscriber")
      manifestsResponseQueue = Option(subscriber)

    case ManifestTries(bestManifests) =>
      log.info(s"Received BestAvailableManifest tries")
      handleManifestTries(bestManifests)

//    case ManifestsFeedSuccess(DqManifests(lastFilename, manifests), createdAt) if manifests.nonEmpty =>
//      log.info(s"Received live manifests")
//      liveManifestsBuffer = liveManifestsBuffer match {
//        case None =>
//          log.info(s"live manifest buffer was empty")
//          Option(ManifestsFeedSuccess(DqManifests(lastFilename, manifests), createdAt))
//        case Some(ManifestsFeedSuccess(DqManifests(_, existingManifests), _)) =>
//          log.info(s"live manifest buffer was not empty. Adding new manifests to it")
//          Option(ManifestsFeedSuccess(DqManifests(lastFilename, existingManifests ++ manifests), createdAt))
//      }
//      manifestsResponseQueue.foreach { queue =>
//        liveManifestsBuffer.foreach { manifestsToSend =>
//          queue.offer(manifestsToSend).onSuccess {
//            case Enqueued =>
//              liveManifestsBuffer = None
//              log.info(s"Enqueued live manifests")
//            case _ => log.info(s"Couldn't enqueue live manifests. Leaving them in the buffer")
//          }
//        }
//      }

    case ArrivalsDiff(arrivals, _) => manifestsRequestQueue.foreach(queue => OfferHandler.offerWithRetries(queue, arrivals.toList, 10))

    case unexpected => log.warn(s"received unexpected ${unexpected.getClass}")
  }

  def handleManifestTries(bestManifests: List[Option[BestAvailableManifest]]): Unit = {
    manifestsResponseQueue.foreach(queue => {
      val successfulManifests = bestManifests.collect { case Some(bm) => bm }
      val bestManifestsResult = BestManifestsFeedSuccess(successfulManifests, SDate.now())
      val replyTo = senderRef()

      OfferHandler.offerWithRetries(queue, bestManifestsResult, 10, Option(() => {
        log.info(s"Acking back to lookup stage")
        replyTo ! Ack
      }))
    })
  }
}
