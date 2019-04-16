package actors

import actors.AckingReceiver.{Ack, StreamInitialized}
import akka.actor.{Actor, ActorRef, Scheduler}
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{Arrival, ArrivalsDiff}
import manifests.ManifestLookupLike
import manifests.graph.ManifestTries
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{BestManifestsFeedSuccess, ManifestsFeedResponse}
import services.{OfferHandler, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps


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

    case ArrivalsDiff(arrivals, _) => manifestsRequestQueue.foreach(queue => OfferHandler.offerWithRetries(queue, arrivals.toList, 10))

    case "complete" =>
      log.info(s"Received 'complete'. Stopping")
      context.stop(self)

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
