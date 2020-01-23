package test

import actors._
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{KillSwitch, Materializer, OverflowStrategy}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{AirportConfig, Arrival, PortCode, Role}
import graphs.SinkToSourceBridge
import manifests.passengers.BestAvailableManifest
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.{SDate, TryRenjin}
import services.crunch.deskrecs.RunnableDeskRecs
import test.TestActors.{TestStaffMovementsActor, _}
import test.feeds.test.{CSVFixtures, TestFixtureFeed, TestManifestsActor}
import test.roles.TestUserRoleProvider

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Success

class TestDrtSystem(override val actorSystem: ActorSystem, override val config: Configuration, override val airportConfig: AirportConfig)(implicit actorMaterializer: Materializer, ec: ExecutionContext)
  extends DrtSystem(actorSystem, config, airportConfig) {

  import DrtStaticParameters._

  override lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  override lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  override lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val testLiveCrunchStateProps: Props = TestCrunchStateActor.props(airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldLiveSnapshots)
  val testForecastCrunchStateProps: Props = TestCrunchStateActor.props(100, "forecast-crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldForecastSnapshots)
  val testManifestsActor: ActorRef = actorSystem.actorOf(Props(classOf[TestManifestsActor]), s"TestActor-APIManifests")

  override lazy val liveCrunchStateActor: AskableActorRef = system.actorOf(testLiveCrunchStateProps, name = "crunch-live-state-actor")
  override lazy val forecastCrunchStateActor: AskableActorRef = system.actorOf(testForecastCrunchStateProps, name = "crunch-forecast-state-actor")
  override lazy val portStateActor: ActorRef = system.actorOf(TestPortStateActor.props(liveCrunchStateActor, forecastCrunchStateActor, now, 2), name = "port-state-actor")
  override lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, expireAfterMillis, params.snapshotIntervalVm), name = "voyage-manifests-actor")
  override lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor], now, timeBeforeThisMonth(now)))
  override lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor], now))
  override lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor], now, time48HoursAgo(now)), "TestActor-StaffMovements")
  override lazy val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestAggregatedArrivalsActor]))

  system.log.warning(s"Using test System")

  val voyageManifestTestSourceGraph: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](100, OverflowStrategy.backpressure)

  override lazy val voyageManifestsHistoricSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = voyageManifestTestSourceGraph


  val testFeed = TestFixtureFeed(system)

  config.getOptional[String]("test.live_fixture_csv").foreach { file =>
    implicit val timeout: Timeout = Timeout(250 milliseconds)
    log.info(s"Loading fixtures from $file")
    val testActor = system.actorSelection(s"akka://${airportConfig.portCode.iata.toLowerCase}-drt-actor-system/user/TestActor-LiveArrivals").resolveOne()
    actorSystem.scheduler.schedule(1 second, 1 day)({
      val day = SDate.now().toISODateOnly
      CSVFixtures.csvPathToArrivalsOnDate(day, file).collect {
        case Success(arrival) =>
          testActor.map(_ ! arrival)
      }
    })

  }

  override def liveArrivalsSource(portCode: PortCode): Source[ArrivalsFeedResponse, Cancellable] = testFeed

  override def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)

  val flexDesks: Boolean = config.get[Boolean]("crunch.flex-desks")

  override def run(): Unit = {
    val startSystem: () => List[KillSwitch] = () => {
      val (manifestRequestsSource, bridge1Ks, manifestRequestsSink) = SinkToSourceBridge[List[Arrival]]
      val (manifestResponsesSource, bridge2Ks, manifestResponsesSink) = SinkToSourceBridge[List[BestAvailableManifest]]

      val cs = startCrunchSystem(
        initialPortState = None,
        initialForecastBaseArrivals = None,
        initialForecastArrivals = None,
        initialLiveBaseArrivals = None,
        initialLiveArrivals = None,
        manifestRequestsSink,
        manifestResponsesSource,
        refreshArrivalsOnStart = false,
        checkRequiredStaffUpdatesOnStartup = false
      )

      val lookupRefreshDue: MillisSinceEpoch => Boolean = (lastLookupMillis: MillisSinceEpoch) => now().millisSinceEpoch - lastLookupMillis > 1000
      val manifestKillSwitch = startManifestsGraph(None, manifestResponsesSink, manifestRequestsSource, lookupRefreshDue)

      val (millisToCrunchActor: ActorRef, crunchKillSwitch) = RunnableDeskRecs.start(portStateActor, airportConfig, now, params.recrunchOnStart, params.forecastMaxDays, 1440, flexDesks, TryRenjin.crunch)
      portStateActor ! SetCrunchActor(millisToCrunchActor)
      portStateActor ! SetSimulationActor(cs.loadsToSimulate)

      subscribeStaffingActors(cs)
      startScheduledFeedImports(cs)

      testManifestsActor ! SubscribeResponseQueue(cs.manifestsLiveResponse)

      List(bridge1Ks, bridge2Ks, manifestKillSwitch, crunchKillSwitch) ++ cs.killSwitches
    }

    val testActors = List(
      baseArrivalsActor,
      forecastArrivalsActor,
      liveArrivalsActor,
      voyageManifestsActor,
      shiftsActor,
      fixedPointsActor,
      staffMovementsActor,
      portStateActor,
      aggregatedArrivalsActor
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

      testActors.foreach { a =>
        a ! ResetActor
      }

      log.info(s"Shutdown triggered")
      startTestSystem()

    case StartTestSystem =>
      startTestSystem()
  }

  def startTestSystem(): Unit = currentKillSwitches = startSystem()
}

case object ResetData

case object StartTestSystem
