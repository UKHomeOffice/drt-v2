package test.feeds.test

import actors.SubscribeResponseQueue
import actors.acking.AckingReceiver.Ack
import akka.actor.{Actor, ActorLogging, Scheduler}
import akka.stream.scaladsl.SourceQueueWithComplete
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.OfferHandler
import test.TestActors.ResetData

import scala.concurrent.ExecutionContext.Implicits.global


case object GetManifests

class TestManifestsActor extends Actor with ActorLogging {

  implicit val scheduler: Scheduler = this.context.system.scheduler

  var maybeManifests: Option[Iterable[VoyageManifest]] = None
  var maybeSubscriber: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None

  override def receive: PartialFunction[Any, Unit] = {
    case VoyageManifests(manifests) =>
      val replyTo = sender()
      log.info(s"Got these VMS: ${manifests.map{m => s"${m.EventCode} ${m.CarrierCode}${m.flightCode}"}}")

      maybeSubscriber match {
        case Some(subscriber) =>
          val onCompletionSendAck = Option(() => replyTo ! Ack)
          OfferHandler.offerWithRetries(subscriber, ManifestsFeedSuccess(DqManifests("", manifests)), 5, onCompletionSendAck)
          maybeManifests = None
        case None =>
          maybeManifests = Option(manifests)
          replyTo ! Ack
      }

    case ResetData =>
      maybeManifests = None
      sender() ! Ack

    case SubscribeResponseQueue(manifestsResponse) =>
      maybeSubscriber = Option(manifestsResponse)
      maybeManifests.foreach(manifests => ManifestsFeedSuccess(DqManifests("", manifests)))
  }
}
