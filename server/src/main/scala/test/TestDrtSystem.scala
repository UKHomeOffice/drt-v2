package test

import actors._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{KillSwitch, Materializer, OverflowStrategy}
import akka.util.Timeout
import drt.shared.{AirportConfig, Role}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.SDate
import test.TestActors.{TestStaffMovementsActor, _}
import test.feeds.test.{CSVFixtures, TestFixtureFeed, TestManifestsActor}
import test.roles.TestUserRoleProvider

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

class TestDrtSystem(override val actorSystem: ActorSystem, override val config: Configuration, override val airportConfig: AirportConfig)(implicit actorMaterializer: Materializer)
  extends DrtSystem(actorSystem, config, airportConfig) {

  import DrtStaticParameters._

  override lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  override lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  override lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val testLiveCrunchStateProps = Props(classOf[TestCrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
  val testForecastCrunchStateProps = Props(classOf[TestCrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)
  val testManifestsActor: ActorRef = actorSystem.actorOf(Props(classOf[TestManifestsActor]), s"TestActor-APIManifests")

  override lazy val liveCrunchStateActor: ActorRef = system.actorOf(testLiveCrunchStateProps, name = "crunch-live-state-actor")
  override lazy val forecastCrunchStateActor: ActorRef = system.actorOf(testForecastCrunchStateProps, name = "crunch-forecast-state-actor")
  override lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, expireAfterMillis, params.snapshotIntervalVm), name = "voyage-manifests-actor")
  override lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor], now, timeBeforeThisMonth(now)))
  override lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor], now))
  override lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor], now, time48HoursAgo(now)), "TestActor-StaffMovements")

  system.log.warning(s"Using test System")

  val voyageManifestTestSourceGraph: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](100, OverflowStrategy.backpressure)

  override lazy val voyageManifestsHistoricSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = voyageManifestTestSourceGraph


  val testFeed = TestFixtureFeed(system)

  config.getOptional[String]("test.live_fixture_csv").foreach { file =>
    implicit val timeout: Timeout = Timeout(250 milliseconds)
    log.info(s"Loading fixtures from $file")
    val testActor = system.actorSelection(s"akka://${airportConfig.portCode.toLowerCase}-drt-actor-system/user/TestActor-LiveArrivals").resolveOne()
    actorSystem.scheduler.schedule(1 second, 1 day)({
      val day = SDate.now().toISODateOnly
      CSVFixtures.csvPathToArrivalsOnDate(day, file).collect {
        case Success(arrival) =>
          testActor.map(_ ! arrival)
      }
    })

  }

  override def liveArrivalsSource(portCode: String): Source[ArrivalsFeedResponse, Cancellable] = testFeed

  override def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)

  override def run(): Unit = {

    val startSystem: () => List[KillSwitch] = () => {
      val cs = startCrunchSystem(None, None, None, None, true, true)
      subscribeStaffingActors(cs)
      startScheduledFeedImports(cs)
      testManifestsActor ! SubscribeResponseQueue(cs.manifestsLiveResponse)
      cs.killSwitches
    }

    val testActors = List(
        baseArrivalsActor,
        forecastArrivalsActor,
        liveArrivalsActor,
        voyageManifestsActor,
        shiftsActor,
        fixedPointsActor,
        staffMovementsActor,
        liveCrunchStateActor,
        forecastCrunchStateActor
    )

    val restartActor: ActorRef = system.actorOf(
        Props(classOf[RestartActor], startSystem, testActors),
        name = "TestActor-ResetData"
    )

    restartActor ! StartTestSystem
  }
}

case class RestartActor(startSystem: () => List[KillSwitch], testActors: List[ActorRef]) extends Actor with ActorLogging {

  var currentKillSwitches: List[KillSwitch] = List()

  override def receive: Receive = {
    case ResetData =>
      log.info(s"About to shut down everything")

        log.info(s"Pressing killswitches")
        currentKillSwitches.zipWithIndex.foreach { case (ks, idx) =>
          log.info(s"Killswitch ${idx + 1}")
          ks.shutdown()
        }

      testActors.foreach(a => {
        a ! ResetActor
      })

      log.info(s"Shutdown triggered")
      startTestSystem()

    case StartTestSystem =>
      startTestSystem()
  }

  def startTestSystem(): Unit = currentKillSwitches = startSystem()
}

case object ResetData

case object StartTestSystem
