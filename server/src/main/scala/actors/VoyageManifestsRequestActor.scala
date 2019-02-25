package actors

import akka.actor.Actor
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.ArrivalsDiff
import manifests.passengers.BestAvailableManifest
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.BestManifestsFeedSuccess
import services.{ManifestLookupLike, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.language.postfixOps
import scala.util.{Success, Try}


class VoyageManifestsRequestActor(portCode: String, manifestLookup: ManifestLookupLike) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var manifestsResponseQueue: Option[SourceQueueWithComplete[Any]] = None
  var requestQueue: Map[Int, Long] = Map()

  override def receive: Receive = {
    case Subscribe(subscriber) =>
      log.info(s"received subscriber")
      manifestsResponseQueue = Option(subscriber)

    case ArrivalsDiff(arrivals, _) =>
      manifestsResponseQueue.foreach(queue => {
        log.info(s"Received ${arrivals.size} requests for manifests and we have a queue to send them to")

        val groupsToProcess = arrivals.grouped(500)

        groupsToProcess.zipWithIndex.foreach { case(arrivalsGroup, idx) =>
          val manifestFutures: Seq[Future[Try[BestAvailableManifest]]] = arrivalsGroup
            .map { arrival => manifestLookup.tryBestAvailableManifest(portCode, arrival.Origin, arrival.voyageNumberPadded, SDate(arrival.Scheduled)) }
            .toSeq

          Future.sequence(manifestFutures).map { bestManifests =>
            val bestSuccessfulManifests = bestManifests.collect { case Success(m) => m }
            queue.offer(BestManifestsFeedSuccess(bestSuccessfulManifests, SDate.now())) map {
              case Enqueued => log.info(s"Enqueued ${manifestFutures.size} estimated manifests")
              case failure => log.info(s"Failed to enqueue ${manifestFutures.size} estimated manifests: $failure")
            }
          }
          log.info(s"Finished processing group ${idx + 1} / ${groupsToProcess.length}")
        }
      })

    case unexpected =>
      log.warn(s"received unexpected ${unexpected.getClass}")
  }

}

