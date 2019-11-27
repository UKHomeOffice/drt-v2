package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import drt.server.feeds.api.ApiProviderLike
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{EventTypes, PortCode}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.feeds.{ManifestsFeedFailure, ManifestsFeedResponse, ManifestsFeedSuccess}
import services.SDate
import services.metrics.{Metrics, StageTimer}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

case class DqManifests(lastSeenFileName: String, manifests: Set[VoyageManifest]) {
  def isEmpty: Boolean = manifests.isEmpty

  def nonEmpty: Boolean = !isEmpty

  def length: Int = manifests.size

  def update(newLastSeenFileName: String, newManifests: Set[VoyageManifest]): DqManifests = {
    val mergedManifests = manifests ++ newManifests
    DqManifests(newLastSeenFileName, mergedManifests)
  }
}

//class VoyageManifestsGraphStage(portCode: PortCode,
//                                provider: ApiProviderLike,
//                                initialLastSeenFileName: String,
//                                minCheckIntervalMillis: MillisSinceEpoch = 30000) extends GraphStage[SourceShape[ManifestsFeedResponse]] {
//  val out: Outlet[ManifestsFeedResponse] = Outlet("Manifests.out")
//  override val shape: SourceShape[ManifestsFeedResponse] = SourceShape(out)
//  val stageName = "voyage-manifests"
//
//  val log: Logger = LoggerFactory.getLogger(getClass)
//
//  val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r
//
//  var maybeResponseToPush: Option[ManifestsFeedResponse] = None
//  var lastSeenFileName: String = initialLastSeenFileName
//  var lastFetchedMillis: MillisSinceEpoch = 0
//
//  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
//    new GraphStageLogic(shape) {
//      setHandler(out, new OutHandler {
//        override def onPull(): Unit = {
//          val timer = StageTimer(stageName, out)
//          fetchAndUpdateState()
//          pushManifests()
//          timer.stopAndReport()
//        }
//      })
//
//      def fetchAndUpdateState(): Unit = {
//        val nowMillis = SDate.now().millisSinceEpoch
//        val millisElapsed = nowMillis - lastFetchedMillis
//        if (millisElapsed < minCheckIntervalMillis) {
//          val millisToSleep = minCheckIntervalMillis - millisElapsed
//          log.info(s"Minimum check interval ${minCheckIntervalMillis}ms not yet reached. Sleeping for ${millisToSleep}ms")
//          Thread.sleep(millisToSleep)
//        }
//
//        maybeResponseToPush = Option(fetchNewManifests(lastSeenFileName))
//        lastFetchedMillis = nowMillis
//      }
//
//      def pushManifests(): Unit = {
//        if (isAvailable(out)) {
//          if (maybeResponseToPush.isEmpty) log.info(s"Nothing to push right now")
//
//          maybeResponseToPush.foreach { responseToPush: ManifestsFeedResponse =>
//            Metrics.counter(s"$stageName", responseToPush.length)
//            push(out, responseToPush)
//          }
//
//          maybeResponseToPush = None
//        }
//      }
//    }
//  }
//
//  def fetchNewManifests(startingFileName: String): ManifestsFeedResponse = {
//    log.info(s"Fetching manifests from files newer than $startingFileName")
//    val eventualFileNameAndManifests = provider
//      .manifestsFuture(startingFileName)
//      .map(fetchedFilesAndManifests => {
//        val (latestFileName, fetchedManifests) = if (fetchedFilesAndManifests.nonEmpty) {
//          val lastSeen = fetchedFilesAndManifests.map { case (fileName, _) => fileName }.max
//          val manifests = fetchedFilesAndManifests.map { case (_, manifest) => jsonStringToManifest(manifest) }.toSet
//          log.info(s"Got ${manifests.size} manifests")
//          (lastSeen, manifests)
//        }
//        else (startingFileName, Set[Option[VoyageManifest]]())
//println(s"manifests: $fetchedManifests")
//        (latestFileName, fetchedManifests)
//      })
//
//    Try {
//      Await.result(eventualFileNameAndManifests, 30 minute)
//    } match {
//      case Success((latestFileName, maybeManifests)) =>
//        log.info(s"Fetched ${maybeManifests.count(_.isDefined)} manifests up to file $latestFileName")
//        lastSeenFileName = latestFileName
//        ManifestsFeedSuccess(DqManifests(latestFileName, maybeManifests.flatten), SDate.now())
//      case Failure(t) =>
//        log.warn(s"Failed to fetch new manifests", t)
//        ManifestsFeedFailure(t.toString, SDate.now())
//    }
//  }
//
//  def jsonStringToManifest(content: String): Option[VoyageManifest] = {
//    VoyageManifestParser.parseVoyagePassengerInfo(content) match {
//      case Success(m) =>
//        if (m.EventCode == EventTypes.DC && m.ArrivalPortCode == portCode) {
//          log.info(s"Using ${m.EventCode} manifest for ${m.ArrivalPortCode} arrival ${m.flightCode}")
//          Option(m)
//        }
//        else None
//      case Failure(t) =>
//        log.error(s"Failed to parse voyage manifest json", t)
//        None
//    }
//  }
//}
