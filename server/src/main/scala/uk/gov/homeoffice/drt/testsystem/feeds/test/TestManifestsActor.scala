package uk.gov.homeoffice.drt.testsystem.feeds.test

import akka.actor.{Actor, ActorLogging, Scheduler}
import akka.pattern.StatusReply
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.server.feeds.{DqManifests, ManifestsFeedResponse, ManifestsFeedSuccess}
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.OfferHandler
import uk.gov.homeoffice.drt.testsystem.TestActors.ResetData

import scala.concurrent.ExecutionContext.Implicits.global


case class SubscribeResponseQueue(subscriber: SourceQueueWithComplete[ManifestsFeedResponse])


class TestManifestsActor extends Actor with ActorLogging {

  implicit val scheduler: Scheduler = this.context.system.scheduler

  private var maybeManifests: Option[Iterable[VoyageManifest]] = None
  var maybeSubscriber: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None

  override def receive: PartialFunction[Any, Unit] = {
    case VoyageManifests(manifests) =>
      val replyTo = sender()
      log.info(s"Got these VMS: ${manifests.map{m => s"${m.EventCode} ${m.CarrierCode}${m.flightCode}"}}")

      maybeSubscriber match {
        case Some(subscriber) =>
          val onCompletionSendAck = Option(() => replyTo ! StatusReply.Ack)
          OfferHandler.offerWithRetries(subscriber, ManifestsFeedSuccess(DqManifests(0L, manifests)), 5, onCompletionSendAck)
          maybeManifests = None
        case None =>
          maybeManifests = Option(manifests)
          replyTo ! StatusReply.Ack
      }

    case ResetData =>
      maybeManifests = None
      sender() ! StatusReply.Ack

    case SubscribeResponseQueue(manifestsResponse) =>
      maybeSubscriber = Option(manifestsResponse)
      maybeManifests.foreach(manifests => ManifestsFeedSuccess(DqManifests(0L, manifests)))
  }
}
