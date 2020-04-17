package test

import actors._
import actors.acking.AckingReceiver.Ack
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.pattern.{AskableActorRef, ask}
import akka.stream.scaladsl.{Source, SourceQueueWithComplete}
import akka.stream.{KillSwitch, Materializer, OverflowStrategy}
import akka.util.Timeout
import drt.auth.Role
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{AirportConfig, Arrival, PortCode}
import graphs.SinkToSourceBridge
import manifests.ManifestLookup
import manifests.passengers.BestAvailableManifest
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import services.SDate
import slickdb.VoyageManifestPassengerInfoTable
import test.TestActors.{TestStaffMovementsActor, _}
import test.feeds.test.{CSVFixtures, TestArrivalsActor, TestFixtureFeed, TestManifestsActor}
import test.roles.TestUserRoleProvider

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.Success

case class TestDrtSystem(config: Configuration, airportConfig: AirportConfig)
                        (implicit val materializer: Materializer,
                         val ec: ExecutionContext,
                         val system: ActorSystem) extends DrtSystemInterface {

  import DrtStaticParameters._

  log.warn("Using test System")

  override val lookup: ManifestLookup = ManifestLookup(VoyageManifestPassengerInfoTable(PostgresTables))

  override val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  override val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val testLiveCrunchStateProps: Props = TestCrunchStateActor.props(airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldLiveSnapshots)
  val testForecastCrunchStateProps: Props = TestCrunchStateActor.props(100, "forecast-crunch-state", airportConfig.queuesByTerminal, now, expireAfterMillis, purgeOldForecastSnapshots)
  val testManifestsActor: ActorRef = system.actorOf(Props(classOf[TestManifestsActor]), s"TestActor-APIManifests")

  override val liveCrunchStateActor: AskableActorRef = system.actorOf(testLiveCrunchStateProps, name = "crunch-live-state-actor")
  override val forecastCrunchStateActor: AskableActorRef = system.actorOf(testForecastCrunchStateProps, name = "crunch-forecast-state-actor")

  override val portStateActor: ActorRef =
    system.actorOf(Props(new TestPortStateActor(liveCrunchStateActor, forecastCrunchStateActor, now, 2)), name = "port-state-actor")

  override val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, expireAfterMillis, params.snapshotIntervalVm), name = "voyage-manifests-actor")
  override val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor], now, timeBeforeThisMonth(now)))
  override val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor], now))
  override val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor], now, time48HoursAgo(now)), "TestActor-StaffMovements")
  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestAggregatedArrivalsActor]))

  val testArrivalActor: ActorRef = system.actorOf(Props(classOf[TestArrivalsActor]), s"TestActor-LiveArrivals")

  val voyageManifestTestSourceGraph: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = Source.queue[ManifestsFeedResponse](100, OverflowStrategy.backpressure)

  override val voyageManifestsHistoricSource: Source[ManifestsFeedResponse, SourceQueueWithComplete[ManifestsFeedResponse]] = voyageManifestTestSourceGraph

  val testFeed: Source[ArrivalsFeedResponse, Cancellable] = TestFixtureFeed(system, testArrivalActor)

  config.getOptional[String]("test.live_fixture_csv").foreach { file =>
    implicit val timeout: Timeout = Timeout(250 milliseconds)
    log.info(s"Loading fixtures from $file")
    val testActor = system.actorSelection(s"akka://${airportConfig.portCode.iata.toLowerCase}-drt-actor-system/user/TestActor-LiveArrivals").resolveOne()
    system.scheduler.schedule(1 second, 1 day)({
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
