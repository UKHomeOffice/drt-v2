package actors

import actors.acking.AckingReceiver.{Ack, StreamInitialized}
import akka.actor.{Actor, ActorRef, Scheduler}
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{Arrival, ArrivalsDiff, SDateLike}
import manifests.ManifestLookupLike
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{BestManifestsFeedSuccess, ManifestsFeedResponse}
import services.{OfferHandler, SDate}

import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global


class VoyageManifestsRequestActor(portCode: String, manifestLookup: ManifestLookupLike, now: () => SDateLike, maxBufferSize: Int, minSecondsBetweenBatches: Int) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var manifestsRequestQueue: Option[ActorRef] = None
  var manifestsResponseQueue: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None
  val manifestBuffer: mutable.ListBuffer[BestAvailableManifest] = mutable.ListBuffer[BestAvailableManifest]()
  var lastBatchSent: MillisSinceEpoch = 0L

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
      handleManifestTries(bestManifests)
      handleManifestBuffer()

    case ArrivalsDiff(arrivals, _) =>
      if (manifestsRequestQueue.isEmpty) log.error(s"Got arrivals before source queue is subscribed")
      manifestsRequestQueue.foreach { queue =>
        log.info(s"got ${arrivals.size} arrivals to enqueue")
        val list = arrivals.values.toList
        log.info(s"offering ${arrivals.size} arrivals to source queue")
        //OfferHandler.offerWithRetries(queue, list, 10)
        queue ! list
      }

    case unexpected => log.warn(s"received unexpected ${unexpected.getClass}")
  }

  def handleManifestTries(bestManifests: List[Option[BestAvailableManifest]]): Unit = {
    manifestBuffer ++= bestManifests.collect { case Some(mf) => mf }
  }

  def handleManifestBuffer(): Unit = {
    if (manifestBuffer.length >= maxBufferSize || lastBatchSent < now().millisSinceEpoch - minSecondsBetweenBatches * 1000) {
      manifestsResponseQueue.foreach(queue => {
        val bestManifestsResult = BestManifestsFeedSuccess(Seq() ++ manifestBuffer, SDate.now())
        log.info(s"Sending batch of ${manifestBuffer.length} BestAvailableManifests")
        manifestBuffer.clear()
        lastBatchSent = now().millisSinceEpoch

        OfferHandler.offerWithRetries(queue, bestManifestsResult, 10, None)
      })
    }

    senderRef() ! Ack
  }
}

case class ManifestTries(tries: List[Option[BestAvailableManifest]]) {
  def +(triesToAdd: List[Option[BestAvailableManifest]]) = ManifestTries(tries ++ triesToAdd)

  def nonEmpty: Boolean = tries.nonEmpty

  def length: Int = tries.length
}

object ManifestTries {
  def empty: ManifestTries = ManifestTries(List())
}
