package services.graphstages

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}
import akka.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.{GetLatestZipFilename, UpdateLatestZipFilename, VoyageManifestsProvider}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.language.postfixOps
import scala.util.matching.Regex


class VoyageManifestsGraphStage(advPaxInfo: VoyageManifestsProvider, voyageManifestsActor: ActorRef) extends GraphStage[SourceShape[VoyageManifests]] {
  val out: Outlet[VoyageManifests] = Outlet[VoyageManifests]("VoyageManifests.out")
  override val shape = SourceShape(out)

  val askableVoyageManifestsActor: AskableActorRef = voyageManifestsActor

  val log: Logger = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var manifestsState: Set[VoyageManifest] = Set()
    var manifestsToPush: Option[Set[VoyageManifest]] = None
    var latestZipFilename: Option[String] = None
    val dqRegex: Regex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

    override def preStart(): Unit = {
      val latestFilenameFuture = askableVoyageManifestsActor.ask(GetLatestZipFilename)(new Timeout(5 seconds))
      latestFilenameFuture.onSuccess {
        case lzf: String =>
          latestZipFilename = Option(lzf)
      }
      Await.ready(latestFilenameFuture, 10 seconds)
      super.preStart()
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        latestZipFilename match {
          case None => log.info(s"We don't have a latestZipFilename yet")
          case Some(lzf) =>
            log.info(s"Sending latestZipFilename: $lzf to VoyageManfestsActor")
            voyageManifestsActor ! UpdateLatestZipFilename(lzf)
            fetchAndPushManifests(lzf)
        }
      }
    })

    def fetchAndPushManifests(lzf: String): Unit = {
      log.info(s"Fetching manifests from files newer than $lzf")
      val manifestsFuture = advPaxInfo.manifestsFuture(lzf)
      manifestsFuture.onSuccess {
        case ms =>
          log.info(s"manifestsFuture Success")
          val nextFetchMaxFilename = if (ms.nonEmpty) {
            val maxFilename = ms.map(_._1).max
            latestZipFilename = Option(maxFilename)
            log.info(s"Set latestZipFilename to '$latestZipFilename'")

            val vms = ms.map(_._2).toSet
            vms -- manifestsState match {
              case newOnes if newOnes.isEmpty =>
                log.info(s"No new manifests")
              case newOnes =>
                log.info(s"${newOnes.size} manifests to push")
                manifestsToPush = Option(newOnes)
                manifestsState = manifestsState ++ newOnes
            }

            manifestsToPush match {
              case None =>
                log.info(s"No manifests to push")
              case Some(manifests) =>
                log.info(s"Pushing ${manifests.size} manifests")
                push(out, VoyageManifests(manifests))
                manifestsToPush = None
            }
            maxFilename
          } else {
            log.info(s"No manifests received")
            lzf
          }
          fetchAndPushManifests(nextFetchMaxFilename)

      }
      manifestsFuture.onFailure {
        case t =>
          log.info(s"manifestsFuture Failure: $t")
          fetchAndPushManifests(lzf)
      }
    }
  }
}
