package test.feeds.test

import actors.SubscribeResponseQueue
import akka.actor.{Actor, ActorLogging}
import akka.stream.scaladsl.SourceQueueWithComplete
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import server.feeds.{ManifestsFeedResponse, ManifestsFeedSuccess}
import services.graphstages.DqManifests
import test.TestActors.ResetActor

import scala.language.postfixOps


case object GetManifests

class TestManifestsActor extends Actor with ActorLogging {

  var maybeManifests: Option[Set[VoyageManifest]] = None
  var maybeSubscriber: Option[SourceQueueWithComplete[ManifestsFeedResponse]] = None

  override def receive: PartialFunction[Any, Unit] = {
    case VoyageManifests(manifests) =>
      log.info(s"Got these VMS: $manifests")

      maybeSubscriber match {
        case Some(subscriber) =>
          subscriber.offer(ManifestsFeedSuccess(DqManifests("", manifests)))
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

