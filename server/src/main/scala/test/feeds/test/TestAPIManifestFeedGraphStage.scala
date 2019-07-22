package test.feeds.test

import actors.SubscribeResponseQueue
import akka.actor.{Actor, ActorLogging, Scheduler}
import akka.stream.scaladsl.SourceQueueWithComplete
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import drt.server.feeds.{ManifestsFeedResponse, ManifestsFeedSuccess}
import services.OfferHandler
import services.graphstages.DqManifests
import test.TestActors.ResetActor

import scala.concurrent.ExecutionContext.Implicits.global
import scala.language.postfixOps


case object GetManifests

class TestManifestsActor extends Actor with ActorLogging {

  implicit val scheduler: Scheduler = this.context.system.scheduler

  var maybeManifests: Option[Set[VoyageManifest]] = None
  var maybeSubscriber: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None

  override def receive: PartialFunction[Any, Unit] = {
    case VoyageManifests(manifests) =>
      log.info(s"Got these VMS: $manifests")

      maybeSubscriber match {
        case Some(subscriber) =>
          OfferHandler.offerWithRetries(subscriber, ManifestsFeedSuccess(DqManifests("", manifests)), 5)
          maybeManifests = None
        case None =>
          maybeManifests = Some(manifests)
      }


    case ResetActor =>
      maybeManifests = None

    case SubscribeResponseQueue(manifestsResponse) =>
      maybeSubscriber = Option(manifestsResponse)
      maybeManifests.map(manifests => ManifestsFeedSuccess(DqManifests("", manifests)))

  }
}

