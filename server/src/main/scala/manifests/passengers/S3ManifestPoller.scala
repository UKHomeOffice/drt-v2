package manifests.passengers

import akka.actor.{ActorSystem, Cancellable}
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.server.feeds.api.ApiProviderLike
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{DqEventCodes, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.{ManifestsFeedFailure, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.SDate
import services.graphstages.DqManifests

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


class S3ManifestPoller(sourceQueue: SourceQueueWithComplete[ManifestsFeedResponse], portCode: String, initialLastSeenFileName: String, provider: ApiProviderLike)
                      (implicit actorSystem: ActorSystem) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  var liveManifestsBuffer: Option[ManifestsFeedSuccess] = None
  var lastSeenFileName: String = initialLastSeenFileName
  var lastFetchedMillis: MillisSinceEpoch = 0

  def fetchNewManifests(startingFileName: String): ManifestsFeedResponse = {
    log.info(s"Fetching manifests from files newer than $startingFileName")
    val eventualFileNameAndManifests = provider
      .manifestsFuture(startingFileName)
      .map(fetchedFilesAndManifests => {
        val (latestFileName, fetchedManifests) = if (fetchedFilesAndManifests.nonEmpty) {
          val lastSeen = fetchedFilesAndManifests.map { case (fileName, _) => fileName }.max
          val manifests = fetchedFilesAndManifests.map { case (_, manifest) => jsonStringToManifest(manifest) }.toSet
          (lastSeen, manifests)
        }
        else (startingFileName, Set[Option[VoyageManifest]]())

        (latestFileName, fetchedManifests)
      })

    Try {
      Await.result(eventualFileNameAndManifests, 30 minute)
    } match {
      case Success((latestFileName, maybeManifests)) =>
        log.info(s"Fetched ${maybeManifests.count(_.isDefined)} manifests up to file $latestFileName")
        ManifestsFeedSuccess(DqManifests(latestFileName, maybeManifests.flatten), SDate.now())
      case Failure(t) =>
        log.warn(s"Failed to fetch new manifests", t)
        ManifestsFeedFailure(t.toString, SDate.now())
    }
  }

  def jsonStringToManifest(content: String): Option[VoyageManifest] = {
    VoyageManifestParser.parseVoyagePassengerInfo(content) match {
      case Success(m) =>
        if (m.EventCode == DqEventCodes.DepartureConfirmed && m.ArrivalPortCode == portCode) {
          log.info(s"Using ${m.EventCode} manifest for ${m.ArrivalPortCode} arrival ${m.flightCode}")
          Option(m)
        }
        else None
      case Failure(t) =>
        log.error(s"Failed to parse voyage manifest json", t)
        None
    }
  }

  def startPollingForManifests(): Cancellable = {
    actorSystem.scheduler.schedule(0 seconds, 1 minute, new Runnable {
      def run(): Unit = {
        fetchNewManifests(lastSeenFileName) match {
          case ManifestsFeedSuccess(DqManifests(latestFileName, manifests), createdAt) =>
            liveManifestsBuffer = updateManifestsBuffer(latestFileName, manifests, createdAt)
            lastSeenFileName = latestFileName
            tryOfferManifests()
          case ManifestsFeedFailure(msg, _) =>
            log.warn(s"Failed to fetch new live manifests: $msg")
        }
      }
    })
  }

  def tryOfferManifests(): Unit = {
    liveManifestsBuffer.foreach { manifestsToSend =>
      sourceQueue.offer(manifestsToSend).map {
        case Enqueued =>
          liveManifestsBuffer = None
        case _ => log.info(s"Couldn't enqueue live manifests. Leaving them in the buffer")
      }.recover {
        case t => log.error(s"Couldn't enqueue live manifests. Leaving them in the buffer", t)
      }
    }
  }

  def updateManifestsBuffer(lastFilename: String, manifests: Set[VoyageManifest], createdAt: SDateLike): Option[ManifestsFeedSuccess] = {
    liveManifestsBuffer match {
      case None =>
        Option(ManifestsFeedSuccess(DqManifests(lastFilename, manifests), createdAt))
      case Some(ManifestsFeedSuccess(DqManifests(_, existingManifests), _)) =>
        Option(ManifestsFeedSuccess(DqManifests(lastFilename, existingManifests ++ manifests), createdAt))
    }
  }
}
