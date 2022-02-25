package manifests.passengers

import akka.Done
import akka.actor.{ActorSystem, Cancellable}
import akka.stream.QueueOfferResult.{Dropped, Enqueued}
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.server.feeds.api.ApiProviderLike
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ManifestsFeedResponse, ManifestsFeedSuccess}
import uk.gov.homeoffice.drt.ports.PortCode

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps


class S3ManifestPoller(sourceQueue: SourceQueueWithComplete[ManifestsFeedResponse], portCode: PortCode, initialLastSeenFileName: String, provider: ApiProviderLike)
                      (implicit actorSystem: ActorSystem) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  var liveManifestsBuffer: Option[ManifestsFeedSuccess] = None
  var lastSeenFileName: String = initialLastSeenFileName
  var currentFileName: String = initialLastSeenFileName

  def processNewManifests(lastFileName: String): Future[Done] = {
    provider
      .manifestAsStream(lastFileName)
      .mapAsync(1)(manifests => sourceQueue.offer(ManifestsFeedSuccess(manifests)).map(r => (manifests.lastSeenFileName, r)))
      .runForeach {
        case (fileName, Dropped) =>
          log.error(s"Failed to enqueue manifests from $fileName")
          lastSeenFileName = fileName
        case (fileName, Enqueued) =>
          log.info(s"Processed & enqueued manifests from $fileName")
          lastSeenFileName = fileName
      }
  }

  def startPollingForManifests(): Cancellable = {
    actorSystem.scheduler.schedule(0 seconds, 1 minute, new Runnable {
      def run(): Unit = {
        processNewManifests(lastSeenFileName)
      }
    })
  }
}
