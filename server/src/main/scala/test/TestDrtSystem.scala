package test

import actors._
import actors.acking.AckingReceiver.Ack
import actors.persistent.RedListUpdatesActor.AddSubscriber
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Props, Status, typed}
import akka.pattern.ask
import akka.persistence.testkit.scaladsl.PersistenceTestKit
import akka.stream.{KillSwitch, Materializer}
import akka.util.Timeout
import drt.server.feeds.Feed
import drt.server.feeds.FeedPoller.Enable
import drt.shared.coachTime.CoachWalkTime
import manifests.passengers.{BestAvailableManifest, ManifestPaxCount}
import manifests.{ManifestLookupLike, UniqueArrivalKey}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import services.SDate
import test.TestActors._
import test.feeds.test._
import test.roles.TestUserRoleProvider
import uk.gov.homeoffice.drt.arrivals.VoyageNumber
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import scala.collection.SortedSet
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.Success

case class MockManifestLookupService()(implicit ec: ExecutionContext, mat: Materializer) extends ManifestLookupLike {
  override def maybeBestAvailableManifest(arrivalPort: PortCode,
                                          departurePort: PortCode,
                                          voyageNumber: VoyageNumber,
                                          scheduled: SDateLike): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] =
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))

  override def historicManifestPax(arrivalPort: PortCode, departurePort: PortCode, voyageNumber: VoyageNumber, scheduled: SDateLike): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {
    Future.successful((UniqueArrivalKey(arrivalPort, departurePort, voyageNumber, scheduled), None))
  }
}

case class TestDrtSystem(airportConfig: AirportConfig)
                        (implicit val materializer: Materializer,
                         val ec: ExecutionContext,
                         val system: ActorSystem,
                         val timeout: Timeout) extends DrtSystemInterface {

  import DrtStaticParameters._

  log.warn("Using test System")

  override val forecastBaseArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new TestAclForecastArrivalsActor(now, expireAfterMillis)), name = "base-arrivals-actor")
  override val forecastArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new TestPortForecastArrivalsActor(now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef = restartOnStop.actorOf(Props(new TestPortLiveArrivalsActor(now, expireAfterMillis)), name = "live-arrivals-actor")

  val manifestLookups: ManifestLookups = ManifestLookups(system)

  override val shiftsActor: ActorRef = restartOnStop.actorOf(Props(new TestShiftsActor(now, timeBeforeThisMonth(now))), "staff-shifts")
  override val fixedPointsActor: ActorRef = restartOnStop.actorOf(Props(new TestFixedPointsActor(now, airportConfig.minutesToCrunch)), "staff-fixed-points")
  override val staffMovementsActor: ActorRef = restartOnStop.actorOf(Props(new TestStaffMovementsActor(now, time48HoursAgo(now), airportConfig.minutesToCrunch)), "TestActor-StaffMovements")
  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new MockAggregatedArrivalsActor()))
  override val manifestsRouterActor: ActorRef = restartOnStop.actorOf(Props(new TestVoyageManifestsActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  override val persistentCrunchQueueActor: ActorRef = system.actorOf(Props(new TestCrunchQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))
  override val persistentDeskRecsQueueActor: ActorRef = system.actorOf(Props(new TestDeskRecsQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))
  override val persistentDeploymentQueueActor: ActorRef = system.actorOf(Props(new TestDeploymentQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))
  override val persistentStaffingUpdateQueueActor: ActorRef = system.actorOf(Props(new TestStaffingUpdateQueueActor(now = () => SDate.now(), airportConfig.crunchOffsetMinutes, airportConfig.minutesToCrunch)))

  override val manifestLookupService: ManifestLookupLike = MockManifestLookupService()
  override val minuteLookups: MinuteLookupsLike = TestMinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)
  val flightLookups: TestFlightLookups = TestFlightLookups(system, now, airportConfig.queuesByTerminal)
  override val flightsRouterActor: ActorRef = flightLookups.flightsActor
  override val queueLoadsRouterActor: ActorRef = minuteLookups.queueLoadsMinutesActor
  override val queuesRouterActor: ActorRef = minuteLookups.queueMinutesActor
  override val staffRouterActor: ActorRef = minuteLookups.staffMinutesActor
  override val queueUpdates: ActorRef = system.actorOf(Props(
    new QueueTestUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      PartitionedPortStateActor.queueUpdatesProps(now, journalType)
    )),
    "updates-supervisor-queues")
  override val staffUpdates: ActorRef = system.actorOf(Props(
    new StaffTestUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      PartitionedPortStateActor.staffUpdatesProps(now, journalType)
    )
  ), "updates-supervisor-staff")
  override val flightUpdates: ActorRef = system.actorOf(Props(
    new TestFlightUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      PartitionedPortStateActor.flightUpdatesProps(now, journalType)
    )
  ), "updates-supervisor-flight")

  override val portStateActor: ActorRef = system.actorOf(
    Props(
      new TestPartitionedPortStateActor(
        flightsRouterActor,
        queuesRouterActor,
        staffRouterActor,
        queueUpdates,
        staffUpdates,
        flightUpdates,
        now,
        airportConfig.queuesByTerminal,
        journalType
      )
    )
  )

  val testManifestsActor: ActorRef = system.actorOf(Props(new TestManifestsActor()), s"TestActor-APIManifests")
  val testArrivalActor: ActorRef = system.actorOf(Props(new TestArrivalsActor()), s"TestActor-LiveArrivals")
  val testFeed: Feed[typed.ActorRef[Feed.FeedTick]] = Feed(TestFixtureFeed(system, testArrivalActor, Feed.actorRefSource), 1.second, 2.seconds)

  val testActors = List(
    forecastBaseArrivalsActor,
    forecastArrivalsActor,
    liveArrivalsActor,
    forecastArrivalsActor,
    portStateActor,
    manifestsRouterActor,
    shiftsActor,
    fixedPointsActor,
    staffMovementsActor,
    aggregatedArrivalsActor,
    testManifestsActor,
    testArrivalActor,
  )

  val restartActor: ActorRef = system.actorOf(Props(new RestartActor(startSystem, testActors)), name = "TestActor-ResetData")

  config.getOptional[String]("test.live_fixture_csv").foreach { file =>
    implicit val timeout: Timeout = Timeout(250 milliseconds)
    log.info(s"Loading fixtures from $file")
    system.scheduler.schedule(1 second, 1 day)({
      val startDay = SDate.now()
      DateRange.utcDateRange(startDay, startDay.addDays(30)).map(day => {
        val arrivals = CSVFixtures.csvPathToArrivalsOnDate(day.toISOString, file)
          .collect {
            case Success(arrival) => arrival
          }
        arrivals.map(testArrivalActor.ask)

        val manifests = arrivals.map(a => {
          MockManifest.manifestForArrival(a)
        })
        Await.ready(testManifestsActor.ask(VoyageManifests(manifests.toSet)), 5 seconds)
      })
    })
  }

  override def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[Feed.FeedTick]] = testFeed

  override def getRoles(config: Configuration,
                        headers: Headers,
                        session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)

  override def run(): Unit = {
    restartActor ! StartTestSystem
  }

  def startSystem: () => List[KillSwitch] = () => {
    val crunchInputs = startCrunchSystem(
      initialPortState = None,
      initialForecastBaseArrivals = None,
      initialForecastArrivals = None,
      initialLiveBaseArrivals = None,
      initialLiveArrivals = None,
      refreshArrivalsOnStart = false,
      startDeskRecs = startDeskRecs(SortedSet(), SortedSet(), SortedSet(), SortedSet()))

    liveActor ! Enable(crunchInputs.liveArrivalsResponse)

    setSubscribers(crunchInputs)

    testManifestsActor ! SubscribeResponseQueue(crunchInputs.manifestsLiveResponse)

    crunchInputs.killSwitches
  }

  val coachWalkTime: CoachWalkTime = CoachWalkTime(airportConfig.portCode)
}


class RestartActor(startSystem: () => List[KillSwitch],
                   testActors: List[ActorRef]) extends Actor with ActorLogging {

  lazy val persistenceTestKit: PersistenceTestKit = PersistenceTestKit(context.system)

  var currentKillSwitches: List[KillSwitch] = List()

  implicit val ec: ExecutionContextExecutor = context.dispatcher

  override def receive: Receive = {
    case ResetData =>
      val replyTo = sender()
      log.info(s"About to shut down everything. Pressing kill switches")

      currentKillSwitches.zipWithIndex.foreach { case (ks, idx) =>
        log.info(s"Kill switch ${idx + 1}")
        ks.shutdown()
      }

      val resetFutures = testActors.map(_.ask(ResetData)(new Timeout(5 second)))
      Future.sequence(resetFutures).onComplete { _ =>
        log.info(s"Shutdown triggered")
        resetInMemoryData()
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

  def resetInMemoryData(): Unit = persistenceTestKit.clearAll()
}

case object StartTestSystem
