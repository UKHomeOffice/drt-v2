package test

import actors._
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.stream.scaladsl.Source
import akka.stream.{ActorMaterializer, KillSwitch}
import drt.server.feeds.test.{TestAPIManifestFeedGraphStage, TestFixtureFeed}
import drt.shared.AirportConfig
import drt.shared.FlightsApi.Flights
import play.api.Configuration
import services.graphstages.DqManifests
import test.TestActors.{TestStaffMovementsActor, _}

class TestDrtSystem(override val actorSystem: ActorSystem, override val config: Configuration, override val airportConfig: AirportConfig)
  extends DrtSystem(actorSystem, config, airportConfig) {

  override val actorMaterializer = ActorMaterializer()

  override lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  override lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  override lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

  val testLiveCrunchStateProps = Props(classOf[TestCrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
  val testForecastCrunchStateProps = Props(classOf[TestCrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)

  override lazy val liveCrunchStateActor: ActorRef = system.actorOf(testLiveCrunchStateProps, name = "crunch-live-state-actor")
  override lazy val forecastCrunchStateActor: ActorRef = system.actorOf(testForecastCrunchStateProps, name = "crunch-forecast-state-actor")
  override lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, expireAfterMillis, snapshotIntervalVm), name = "voyage-manifests-actor")
  override lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor]))
  override lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor]))
  override lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor]))

  system.log.warning(s"Using test System")
  val voyageManifestTestSourceGraph: Source[DqManifests, NotUsed] = Source.fromGraph(new TestAPIManifestFeedGraphStage(system))

  system.log.info(s"Here's the Graph $voyageManifestTestSourceGraph")
  override lazy val voyageManifestsStage: Source[DqManifests, NotUsed] = voyageManifestTestSourceGraph
  val testFeed = TestFixtureFeed(system).map(Flights)

  override def liveArrivalsSource(portCode: String): Source[Flights, Cancellable] = testFeed

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

  def startTestSystem() = {
    currentKillSwitches = startSystem()
  }
}

case object ResetData

case object StartTestSystem
