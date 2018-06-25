package test

import actors._
import akka.NotUsed
import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.stream.{ActorMaterializer, KillSwitch}
import akka.stream.scaladsl.Source
import drt.server.feeds.test.{TestAPIManifestFeedGraphStage, TestFixtureFeed}
import drt.shared.{AirportConfig, Arrival}
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.Flights
import play.api.Configuration
import services.crunch.CrunchSystem
import services.graphstages.DqManifests
import test.TestActors.{TestStaffMovementsActor, _}

import scala.util.{Failure, Success}

class TestDrtSystem(override val actorSystem: ActorSystem, override val config: Configuration, override val airportConfig: AirportConfig)
  extends DrtSystem(actorSystem, config, airportConfig){

  override val actorMaterializer = ActorMaterializer()

  override lazy val baseArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastBaseArrivalsActor], now, expireAfterMillis), name = "base-arrivals-actor")
  override lazy val forecastArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestForecastPortArrivalsActor], now, expireAfterMillis), name = "forecast-arrivals-actor")
  override lazy val liveArrivalsActor: ActorRef = system.actorOf(Props(classOf[TestLiveArrivalsActor], now, expireAfterMillis), name = "live-arrivals-actor")

//  override val liveCrunchStateProps = Props(classOf[TestCrunchStateActor], airportConfig.portStateSnapshotInterval, "crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldLiveSnapshots)
//  override val forecastCrunchStateProps = Props(classOf[TestCrunchStateActor], 100, "forecast-crunch-state", airportConfig.queues, now, expireAfterMillis, purgeOldForecastSnapshots)

//  override lazy val liveCrunchStateActor: ActorRef = system.actorOf(liveCrunchStateProps, name = "crunch-live-state-actor")
//  override lazy val forecastCrunchStateActor: ActorRef = system.actorOf(forecastCrunchStateProps, name = "crunch-forecast-state-actor")
  override lazy val voyageManifestsActor: ActorRef = system.actorOf(Props(classOf[TestVoyageManifestsActor], now, expireAfterMillis, snapshotIntervalVm), name = "voyage-manifests-actor")
  override lazy val shiftsActor: ActorRef = system.actorOf(Props(classOf[TestShiftsActor]))
  override lazy val fixedPointsActor: ActorRef = system.actorOf(Props(classOf[TestFixedPointsActor]))
  override lazy val staffMovementsActor: ActorRef = system.actorOf(Props(classOf[TestStaffMovementsActor]))


  system.log.warning(s"Using test System")
  val graph: Source[DqManifests, NotUsed] = Source.fromGraph(new TestAPIManifestFeedGraphStage(system))

  system.log.info(s"Here's the Graph $graph")
  override lazy val voyageManifestsStage: Source[DqManifests, NotUsed] = graph
  val testFeed = TestFixtureFeed(system).map(Flights)
  override def liveArrivalsSource(portCode: String): Source[Flights, Cancellable] = testFeed


//
//  val restartActor: ActorRef = system.actorOf(Props(classOf[RestartActor], cs.baseArrivals), name="TestActor-ResetData")

}
case class RestartActor(feed: Cancellable) extends Actor with ActorLogging {
  override def receive: Receive = {
    case ResetData =>
      log.info(s"About to shut down everything")

      feed.cancel()
      log.info(s"Shutdown triggered")
  }
}
case object ResetData
