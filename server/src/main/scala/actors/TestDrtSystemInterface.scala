package actors

import akka.actor.{ActorRef, ActorSystem, Props, typed}
import akka.pattern.ask
import akka.stream.KillSwitch
import akka.util.Timeout
import drt.server.feeds.FeedPoller.Enable
import drt.server.feeds._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import play.api.Configuration
import uk.gov.homeoffice.drt.crunchsystem.{PersistentStateActors, ReadRouteUpdateActorsLike}
import uk.gov.homeoffice.drt.db.SubscribeResponseQueue
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.testsystem.RestartActor
import uk.gov.homeoffice.drt.testsystem.crunchsystem.TestPersistentStateActors
import uk.gov.homeoffice.drt.testsystem.feeds.test.{CSVFixtures, MockManifest, TestArrivalsActor, TestFixtureFeed, TestManifestsActor}
import uk.gov.homeoffice.drt.time.{SDate, MilliDate => _}

import scala.collection.SortedSet
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.Success

trait TestDrtSystemActorsLike {
  val testManifestsActor: ActorRef
  val testArrivalActor: ActorRef
  val testFeed: Feed[typed.ActorRef[Feed.FeedTick]]
  val restartActor: ActorRef
}

case class TestDrtSystemActors(applicationService: ApplicationService,
                               feedService: FeedService,
                               actorService: ReadRouteUpdateActorsLike,
                               persistentActors: PersistentStateActors,
                               config: Configuration)
                              (implicit system: ActorSystem, ec: ExecutionContext) extends TestDrtSystemActorsLike {
  val log: Logger = LoggerFactory.getLogger(getClass)
  override val testManifestsActor: ActorRef = system.actorOf(Props(new TestManifestsActor()), s"TestActor-APIManifests")
  override val testArrivalActor: ActorRef = system.actorOf(Props(new TestArrivalsActor()), s"TestActor-LiveArrivals")
  override val testFeed: Feed[typed.ActorRef[Feed.FeedTick]] = Feed(TestFixtureFeed(system, testArrivalActor, Feed.actorRefSource), 1.second, 2.seconds)

  override val restartActor: ActorRef = system.actorOf(Props(new RestartActor(startSystem)), name = "TestActor-ResetData")

//  def liveArrivalsSource(portCode: PortCode): Feed[typed.ActorRef[Feed.FeedTick]] = testFeed


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

  config.getOptional[String]("test.live_fixture_csv").foreach { file =>
    implicit val timeout: Timeout = Timeout(250 milliseconds)
    log.info(s"Loading fixtures from $file")
    system.scheduler.scheduleAtFixedRate(1 second, 1 day)(
      () => {
        val startDay = SDate.now()
        DateRange.utcDateRange(startDay, startDay.addDays(30)).map(day => {
          val arrivals = CSVFixtures
            .csvPathToArrivalsOnDate(day.toISOString, file)
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

    testManifestsActor ! SubscribeResponseQueue(crunchInputs.manifestsLiveResponseSource)

    crunchInputs.killSwitches
  }
}


