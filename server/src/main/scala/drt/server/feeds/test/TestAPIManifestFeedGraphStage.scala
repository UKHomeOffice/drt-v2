package drt.server.feeds.test

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.pattern.AskableActorRef
import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{ActorMaterializer, Attributes, Outlet, SourceShape}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import server.feeds.{FeedResponse, ManifestsFeedSuccess}
import services.SDate
import services.graphstages.DqManifests
import test.TestActors.ResetActor

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps


class TestAPIManifestFeedGraphStage(actorSystem: ActorSystem) extends GraphStage[SourceShape[FeedResponse]] {
  implicit val system: ActorSystem = actorSystem
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  val log: Logger = LoggerFactory.getLogger(getClass)
  val minCheckIntervalMillis = 3000

  val out: Outlet[FeedResponse] = Outlet("manifestsOut")
  override val shape: SourceShape[FeedResponse] = SourceShape(out)

  var dqManifestsState: DqManifests = DqManifests("today", Set())
  var lastFetchedMillis: MillisSinceEpoch = 0

  val askableTestManifestsActorActor: AskableActorRef = actorSystem.actorOf(Props(classOf[TestManifestsActor]), s"TestActor-APIManifests")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          fetchAndUpdateState()
          pushManifests()
        }
      })

      def pushManifests(): Unit = {
        if (isAvailable(out)) {

          push(out, ManifestsFeedSuccess(dqManifestsState))

          dqManifestsState = dqManifestsState.copy(manifests = Set())
        }
      }
    }
  }

  def fetchAndUpdateState(): Unit = {
    val nowMillis = SDate.now().millisSinceEpoch
    val millisElapsed = nowMillis - lastFetchedMillis
    if (millisElapsed < minCheckIntervalMillis) {
      val millisToSleep = minCheckIntervalMillis - millisElapsed
      log.info(s"Minimum check interval ${minCheckIntervalMillis}ms not yet reached. Sleeping for ${millisToSleep}ms")
      Thread.sleep(millisToSleep)
    }

    implicit val timeout: Timeout = Timeout(300 milliseconds)
    dqManifestsState = Await.result(askableTestManifestsActorActor.ask(GetManifests).map {
      case VoyageManifests(manifests) =>
        log.info(s"Got these manifests from the actor: $manifests")
        dqManifestsState.update("today", manifests)
    }, 10 seconds)
    lastFetchedMillis = nowMillis
  }
}

case object GetManifests

class TestManifestsActor extends Actor with ActorLogging {

  var testManifests: VoyageManifests = VoyageManifests(Set())

  override def receive: PartialFunction[Any, Unit] = {
    case vms: VoyageManifests =>
      log.info(s"Got these VMS: $vms")
      testManifests = testManifests.copy(manifests = testManifests.manifests ++ vms.manifests)

    case GetManifests =>

      sender ! VoyageManifests(testManifests.manifests)
      testManifests.copy(manifests = Set())

    case ResetActor =>
      testManifests = VoyageManifests(Set())
  }
}

