package actors

import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.stream.KillSwitch
import drt.server.feeds.FeedPoller.Enable
import drt.server.feeds._
import uk.gov.homeoffice.drt.crunchsystem.{PersistentStateActors, ReadRouteUpdateActorsLike}
import uk.gov.homeoffice.drt.db.SubscribeResponseQueue
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.testsystem.RestartActor
import uk.gov.homeoffice.drt.testsystem.crunchsystem.TestPersistentStateActors
import uk.gov.homeoffice.drt.testsystem.feeds.test.{TestArrivalsActor, TestFixtureFeed, TestManifestsActor}
import uk.gov.homeoffice.drt.time.{MilliDate => _}

import scala.collection.SortedSet
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

trait TestDrtSystemActorsLike {
  val testManifestsActor: ActorRef
  val testArrivalActor: ActorRef
  val testFeed: Feed[typed.ActorRef[Feed.FeedTick]]
  val restartActor: ActorRef
}

case class TestDrtSystemActors(applicationService: ApplicationService, feedService: FeedService, actorService: ReadRouteUpdateActorsLike, persistentActors: PersistentStateActors)(implicit system: ActorSystem) extends TestDrtSystemActorsLike {
  override val testManifestsActor: ActorRef = system.actorOf(Props(new TestManifestsActor()), s"TestActor-APIManifests")
  override val testArrivalActor: ActorRef = system.actorOf(Props(new TestArrivalsActor()), s"TestActor-LiveArrivals")
  override val testFeed: Feed[typed.ActorRef[Feed.FeedTick]] = Feed(TestFixtureFeed(system, testArrivalActor, Feed.actorRefSource), 1.second, 2.seconds)

  override val restartActor: ActorRef = system.actorOf(Props(new RestartActor(startSystem)), name = "TestActor-ResetData")

  def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[Feed.FeedTick]] = testFeed

  restartActor ! RestartActor.AddResetActors(Seq(
    persistentActors.forecastBaseArrivalsActor,
    persistentActors.forecastArrivalsActor,
    persistentActors.liveArrivalsActor,
    persistentActors.liveBaseArrivalsActor,
    persistentActors.manifestsRouterActor,
    persistentActors.crunchQueueActor,
    persistentActors.deskRecsQueueActor,
    persistentActors.deploymentQueueActor,
    persistentActors.staffingQueueActor,
    persistentActors.aggregatedArrivalsActor,
    actorService.portStateActor,
    testManifestsActor,
    testArrivalActor,
    actorService.liveShiftsReadActor,
    actorService.liveFixedPointsReadActor,
    actorService.liveStaffMovementsReadActor
  ))

//  def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[Feed.FeedTick]] = testFeed

  private def startSystem: () => List[KillSwitch] = () => {
    val crunchInputs = applicationService.startCrunchSystem(
      actors = applicationService.persistentStateActors,
      initialPortState = None,
      initialForecastBaseArrivals = None,
      initialForecastArrivals = None,
      initialLiveBaseArrivals = None,
      initialLiveArrivals = None,
      refreshArrivalsOnStart = false,
      startUpdateGraphs = applicationService.startUpdateGraphs(applicationService.persistentStateActors, SortedSet(), SortedSet(), SortedSet(), SortedSet())
    )

    feedService.liveActor ! Enable(crunchInputs.liveArrivalsResponse)

    applicationService.setSubscribers(crunchInputs, applicationService.persistentStateActors.manifestsRouterActor)

//    testManifestsActor ! SubscribeResponseQueue(crunchInputs.manifestsLiveResponseSource)

    crunchInputs.killSwitches
  }
}


