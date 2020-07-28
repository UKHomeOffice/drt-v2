package test

import actors._
import actors.acking.AckingReceiver.Ack
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props, Status}
import akka.pattern.ask
import akka.persistence.inmemory.extension.{InMemoryJournalStorage, InMemorySnapshotStorage, StorageExtension}
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, KillSwitch}
import akka.util.Timeout
import drt.auth.Role
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival
import drt.shared.{AirportConfig, MilliTimes, PortCode}
import graphs.SinkToSourceBridge
import manifests.passengers.BestAvailableManifest
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.ArrivalsFeedResponse
import services.SDate
import test.TestActors.{TestStaffMovementsActor, _}
import test.feeds.test.{CSVFixtures, TestArrivalsActor, TestFixtureFeed, TestManifestsActor}
import test.roles.TestUserRoleProvider

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.Success

case class TestDrtSystem(config: Configuration, airportConfig: AirportConfig)
                        (implicit val materializer: ActorMaterializer,
                         val ec: ExecutionContext,
                         val system: ActorSystem) extends DrtSystemInterface {

  import DrtStaticParameters._

  log.warn("Using test System")

  override val baseArrivalsActor: ActorRef = system.actorOf(Props(new TestForecastBaseArrivalsActor(now, expireAfterMillis)), name = "base-arrivals-actor")
  override val forecastArrivalsActor: ActorRef = system.actorOf(Props(new TestForecastPortArrivalsActor(now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef = system.actorOf(Props(new TestLiveArrivalsActor(now, expireAfterMillis)), name = "live-arrivals-actor")
  override val voyageManifestsActor: ActorRef = system.actorOf(Props(new TestVoyageManifestsActor(now, expireAfterMillis, params.snapshotIntervalVm)), name = "voyage-manifests-actor")
  override val shiftsActor: ActorRef = system.actorOf(Props(new TestShiftsActor(now, timeBeforeThisMonth(now))))
  override val fixedPointsActor: ActorRef = system.actorOf(Props(new TestFixedPointsActor(now)))
  override val staffMovementsActor: ActorRef = system.actorOf(Props(new TestStaffMovementsActor(now, time48HoursAgo(now))), "TestActor-StaffMovements")
  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new TestAggregatedArrivalsActor()))
  override val crunchQueueActor: ActorRef = system.actorOf(Props(new TestCrunchQueueActor(now, journalType, airportConfig.crunchOffsetMinutes)), name = "crunch-queue-actor")
  override val deploymentQueueActor: ActorRef = system.actorOf(Props(new TestDeploymentQueueActor(now, journalType, airportConfig.crunchOffsetMinutes)), name = "staff-queue-actor")

  override val lookups: MinuteLookupsLike = TestMinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  override val portStateActor: ActorRef = TestPartitionedPortStateActor(now, airportConfig, StreamingJournal.forConfig(config), lookups)

  val testManifestsActor: ActorRef = system.actorOf(Props(new TestManifestsActor()), s"TestActor-APIManifests")
  val testArrivalActor: ActorRef = system.actorOf(Props(new TestArrivalsActor()), s"TestActor-LiveArrivals")
  val testFeed: Source[ArrivalsFeedResponse, Cancellable] = TestFixtureFeed(system, testArrivalActor)

  val testActors = List(
    baseArrivalsActor,
    forecastArrivalsActor,
    liveArrivalsActor,
    forecastArrivalsActor,
    portStateActor,
    voyageManifestsActor,
    shiftsActor,
    fixedPointsActor,
    staffMovementsActor,
    aggregatedArrivalsActor,
    testManifestsActor,
    testArrivalActor,
    crunchQueueActor,
    deploymentQueueActor
  )

  val restartActor: ActorRef = system.actorOf(Props(new RestartActor(startSystem, testActors)), name = "TestActor-ResetData")

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

  override def run(): Unit = {
    restartActor ! StartTestSystem
  }

  def startSystem(implicit system: ActorSystem, materializer: ActorMaterializer): () => List[KillSwitch] = () => {
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
      startDeskRecs = startDeskRecs)

    val lookupRefreshDue: MillisSinceEpoch => Boolean = (lastLookupMillis: MillisSinceEpoch) => now().millisSinceEpoch - lastLookupMillis > 1000
    val manifestKillSwitch = startManifestsGraph(None, manifestResponsesSink, manifestRequestsSource, lookupRefreshDue)

    subscribeStaffingActors(cs)
    startScheduledFeedImports(cs)

    testManifestsActor ! SubscribeResponseQueue(cs.manifestsLiveResponse)

    List(bridge1Ks, bridge2Ks, manifestKillSwitch) ++ cs.killSwitches
  }
}

class RestartActor(startSystem: () => List[KillSwitch],
                   testActors: List[ActorRef]) extends Actor with ActorLogging {

  var currentKillSwitches: List[KillSwitch] = List()

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case ResetData =>
      val replyTo = sender()

      resetInMemoryData()

      log.info(s"About to shut down everything. Pressing kill switches")

      currentKillSwitches.zipWithIndex.foreach { case (ks, idx) =>
        log.info(s"Kill switch ${idx + 1}")
        ks.shutdown()
      }

      Future.sequence(testActors.map(_.ask(ResetData)(new Timeout(5 second)))).onComplete { _ =>
        log.info(s"Shutdown triggered")
        startTestSystem()
        replyTo ! Ack
      }

    case Status.Success(_) =>
      log.info(s"Got a Status acknowledgement from InMemoryJournalStorage")

    case Status.Failure(t) =>
      log.error("Got a failure message", t)

    case StartTestSystem => startTestSystem()
    case u => log.error(s"Received unexpected message: ${u.getClass}")
  }

  def startTestSystem(): Unit = currentKillSwitches = startSystem()

  def resetInMemoryData(): Unit = {
    StorageExtension(context.system).journalStorage ! InMemoryJournalStorage.ClearJournal
    StorageExtension(context.system).snapshotStorage ! InMemorySnapshotStorage.ClearSnapshots
  }
}

case object StartTestSystem
