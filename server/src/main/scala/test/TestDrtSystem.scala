package test

import actors._
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.stream.{KillSwitch, Materializer}
import akka.stream.scaladsl.Source
import drt.server.feeds.test.{TestAPIManifestFeedGraphStage, TestFixtureFeed}
import drt.shared.{AirportConfig, Role}
import play.api.Configuration
import play.api.mvc.{Headers, Session}
import server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import test.TestActors.{TestStaffMovementsActor, _}

class TestDrtSystem(override val actorSystem: ActorSystem, override val config: Configuration, override val airportConfig: AirportConfig)(implicit actorMaterializer: Materializer)
  extends DrtSystem(actorSystem, config, airportConfig) {

  import DrtStaticParameters._

  override lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, fortyEightHoursMillis), name = "base-arrivals-actor")
  override lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, fortyEightHoursMillis), name = "forecast-arrivals-actor")
  override lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, fortyEightHoursMillis), name = "live-arrivals-actor")

  val testLiveCrunchStateProps = Props(classOf[TestCrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, fortyEightHoursMillis, purgeOldLiveSnapshots)
  val testForecastCrunchStateProps = Props(classOf[TestCrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, fortyEightHoursMillis, purgeOldForecastSnapshots)

  override lazy val liveCrunchStateActor: ActorRef = system.actorOf(testLiveCrunchStateProps, name = "crunch-live-state-actor")
  override lazy val forecastCrunchStateActor: ActorRef = system.actorOf(testForecastCrunchStateProps, name = "crunch-forecast-state-actor")
  override lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, fortyEightHoursMillis, params.snapshotIntervalVm), name = "voyage-manifests-actor")
  override lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor], now, timeBeforeThisMonth(now)))
  override lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor], now))
  override lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor], now, time48HoursAgo(now)), "TestActor-StaffMovements")

  system.log.warning(s"Using test System")
  val voyageManifestTestSourceGraph: Source[ManifestsFeedResponse, NotUsed] = Source.fromGraph(new TestAPIManifestFeedGraphStage)

  system.log.info(s"Here's the Graph $voyageManifestTestSourceGraph")
  override lazy val voyageManifestsStage: Source[ManifestsFeedResponse, NotUsed] = voyageManifestTestSourceGraph
  val testFeed = TestFixtureFeed(system)

  override def liveArrivalsSource(portCode: String): Source[ArrivalsFeedResponse, Cancellable] = testFeed

  override def getRoles(config: Configuration, headers: Headers, session: Session): Set[Role] = TestUserRoleProvider.getRoles(config, headers, session)

  override def run(): Unit = {

    val startSystem = () => {
      val cs = startCrunchSystem(None, None, None, None, true)
      subscribeStaffingActors(cs)
      startScheduledFeedImports(cs)
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

      currentKillSwitches.foreach(_.shutdown())
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
