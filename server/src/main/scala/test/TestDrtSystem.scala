package test

import actors._
import actors.acking.AckingReceiver.Ack
import actors.daily.TerminalDayQueuesActor
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, PoisonPill, Props}
import akka.pattern.{AskableActorRef, ask}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{KillSwitch, Materializer, OverflowStrategy}
import akka.util.Timeout
import drt.auth.Role
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, Arrival, MilliTimes, PortCode, TM, TQM}
import graphs.SinkToSourceBridge
import manifests.passengers.BestAvailableManifest
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.SDate
import services.graphstages.Crunch
import test.TestActors.{TestStaffMovementsActor, _}
import test.feeds.test.{CSVFixtures, TestArrivalsActor, TestFixtureFeed, TestManifestsActor}
import test.roles.TestUserRoleProvider

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.Success

trait TestDrtSystemInterface {
  val restartActor: ActorRef
  val testArrivalActor: ActorRef
  val testManifestsActor: ActorRef
}

class TestDrtSystem(override val actorSystem: ActorSystem,
                    override val config: Configuration,
                    override val airportConfig: AirportConfig)
                   (implicit actorMaterializer: Materializer, ec: ExecutionContext)
  extends DrtSystem(actorSystem, config, airportConfig) with TestDrtSystemInterface {

  import DrtStaticParameters._

  override lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  override lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  override lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val testLiveCrunchStateProps: Props = TestCrunchStateActor.props(airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldLiveSnapshots)
  val testForecastCrunchStateProps: Props = TestCrunchStateActor.props(100, "forecast-crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldForecastSnapshots)
  val testManifestsActor: ActorRef = actorSystem.actorOf(Props(classOf[TestManifestsActor]), s"TestActor-APIManifests")

  override lazy val liveCrunchStateActor: AskableActorRef = system.actorOf(testLiveCrunchStateProps, name = "crunch-live-state-actor")
  override lazy val forecastCrunchStateActor: AskableActorRef = system.actorOf(testForecastCrunchStateProps, name = "crunch-forecast-state-actor")

  override lazy val portStateActor: ActorRef = if (config.get[Boolean]("feature-flags.use-partitioned-state")) {
    println(s"\n\n*** using partitioned state\n\n")
    val lookups: MinuteLookups = MinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
    val flightsActor: ActorRef = system.actorOf(Props(new TestFlightsStateActor(None, Sizes.oneMegaByte, "crunch-live-state-actor", airportConfig.queuesByTerminal, now, expireAfterMillis)))
    val reset: Class[_] => (Terminal, MillisSinceEpoch) => Future[Any] = (clazz: Class[_]) => (terminal: Terminal, day: MillisSinceEpoch) => {
      val date = SDate(day, Crunch.europeLondonTimeZone)
      val actor = system.actorOf(Props(clazz, date.getFullYear(), date.getMonth(), date.getDate(), terminal, now))
      actor.ask(ResetActor)(new Timeout(5 seconds)).map { _ => actor ! PoisonPill }
    }
    val queuesActor: ActorRef = system.actorOf(Props(new TestMinutesActor[CrunchMinute, TQM](now, lookups.queuesByTerminal.keys, lookups.primaryCrunchLookup, lookups.secondaryCrunchLookup, lookups.updateCrunchMinutes, reset(classOf[TestTerminalDayQueuesActor]))))
    val staffActor: ActorRef = system.actorOf(Props(new TestMinutesActor[StaffMinute, TM](now, lookups.queuesByTerminal.keys, lookups.primaryStaffLookup, lookups.secondaryStaffLookup, lookups.updateStaffMinutes, reset(classOf[TestTerminalDayStaffActor]))))
    system.actorOf(Props(new TestPartitionedPortStateActor(flightsActor, queuesActor, staffActor, now)))
  } else {
    println(s"\n\n*** using non-partitioned state\n\n")
    system.actorOf(Props(new TestPortStateActor(liveCrunchStateActor, forecastCrunchStateActor, now, 2)), name = "port-state-actor")
  }

  override lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, expireAfterMillis, params.snapshotIntervalVm), name = "voyage-manifests-actor")
  override lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor], now, timeBeforeThisMonth(now)))
  override lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor], now))
  override lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor], now, time48HoursAgo(now)), "TestActor-StaffMovements")
  override lazy val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestAggregatedArrivalsActor]))
  val testArrivalActor: ActorRef = actorSystem.actorOf(Props(classOf[TestArrivalsActor]), s"TestActor-LiveArrivals")

  system.log.warning(s"Using test System")

  val voyageManifestTestSourceGraph: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](100, OverflowStrategy.backpressure)

  override lazy val voyageManifestsHistoricSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = voyageManifestTestSourceGraph

  val testFeed: Source[ArrivalsFeedResponse, Cancellable] = TestFixtureFeed(system, testArrivalActor)

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

  override def getRoles(config: Configuration,
                        headers: Headers,
                        session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)

  val flexDesks: Boolean = config.get[Boolean]("crunch.flex-desks")

  val testActors = List(
    baseArrivalsActor,
    forecastArrivalsActor,
    liveArrivalsActor,
    voyageManifestsActor,
    shiftsActor,
    fixedPointsActor,
    staffMovementsActor,
    portStateActor,
    aggregatedArrivalsActor,
    testManifestsActor,
    testArrivalActor
    )

  val restartActor: ActorRef = system.actorOf(Props(RestartActor(startSystem, testActors)), name = "TestActor-ResetData")

  override def run(): Unit = {
    restartActor ! StartTestSystem
  }

  def startSystem: () => List[KillSwitch] = () => {
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
      checkRequiredStaffUpdatesOnStartup = false,
      useLegacyDeployments,
      startDeskRecs = startDeskRecs)

    val lookupRefreshDue: MillisSinceEpoch => Boolean = (lastLookupMillis: MillisSinceEpoch) => now().millisSinceEpoch - lastLookupMillis > 1000
    val manifestKillSwitch = startManifestsGraph(None, manifestResponsesSink, manifestRequestsSource, lookupRefreshDue)

    portStateActor ! SetSimulationActor(cs.loadsToSimulate)

    subscribeStaffingActors(cs)
    startScheduledFeedImports(cs)

    testManifestsActor ! SubscribeResponseQueue(cs.manifestsLiveResponse)

    List(bridge1Ks, bridge2Ks, manifestKillSwitch) ++ cs.killSwitches
  }
}

case class RestartActor(startSystem: () => List[KillSwitch],
                        testActors: List[ActorRef]) extends Actor with ActorLogging {

  var currentKillSwitches: List[KillSwitch] = List()

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case ResetActor =>
      val replyTo = sender()

      log.info(s"About to shut down everything. Pressing kill switches")

      currentKillSwitches.zipWithIndex.foreach { case (ks, idx) =>
        log.info(s"Killswitch ${idx + 1}")
        ks.shutdown()
      }

      Future.sequence(testActors.map(_.ask(ResetActor)(new Timeout(1 second)))).onComplete { _ =>
        log.info(s"Shutdown triggered")
        startTestSystem()
        replyTo ! Ack
      }

    case StartTestSystem => startTestSystem()
  }

  def startTestSystem(): Unit = currentKillSwitches = startSystem()
}

case object ResetData

case object StartTestSystem
