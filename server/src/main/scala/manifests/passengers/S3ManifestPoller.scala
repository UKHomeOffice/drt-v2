package manifests.passengers

import akka.NotUsed
import akka.actor.{ActorSystem, Cancellable, Scheduler}
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, SinkQueueWithCancel, Source, SourceQueueWithComplete}
import drt.server.feeds.api.ApiProviderLike
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DqEventCodes
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.{ManifestsFeedFailure, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.graphstages.DqManifests
import services.{OfferHandler, SDate}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}


class S3ManifestPoller(sourceQueue: SourceQueueWithComplete[ManifestsFeedResponse], portCode: String, initialLastSeenFileName: String, provider: ApiProviderLike)
                      (implicit actorSystem: ActorSystem, materializer: Materializer) {

  val log: Logger = LoggerFactory.getLogger(getClass)

  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  var maybeResponseToPush: Option[ManifestsFeedResponse] = None
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
          log.info(s"Got ${manifests.size} manifests")
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
        lastSeenFileName = latestFileName
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

  val tickingSource: Source[Unit, Cancellable] = Source.tick(0 seconds, 1 minute, NotUsed).map(_ => {
    implicit val scheduler: Scheduler = actorSystem.scheduler

    log.info(s"Ticking API stuff")
    OfferHandler.offerWithRetries(sourceQueue, fetchNewManifests(lastSeenFileName), 5)
  })

  def startPollingForManifests(): SinkQueueWithCancel[Unit] = tickingSource.runWith(Sink.queue())
}
