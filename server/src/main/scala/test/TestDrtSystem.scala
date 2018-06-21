package test

import actors._
import akka.NotUsed
import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorLogging, ActorRef, ActorSystem, Cancellable, Props}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import drt.server.feeds.test.{TestAPIManifestFeedGraphStage, TestFixtureFeed}
import drt.shared.AirportConfig
import drt.shared.FlightsApi.Flights
import play.api.Configuration
import services.graphstages.DqManifests
import test.TestActors.{TestStaffMovementsActor, _}

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

  val restartActor: ActorRef = system.actorOf(Props(classOf[RestartActor], actorMaterializer), name="TestActor-ResetData")

  system.log.warning(s"Using test System")

  val graph = Source.fromGraph(new TestAPIManifestFeedGraphStage(system))
  system.log.info(s"Here's the Graph $graph")
  override lazy val voyageManifestsStage: Source[DqManifests, NotUsed] = graph

  override def liveArrivalsSource(portCode: String): Source[Flights, Cancellable] = TestFixtureFeed(system).map(Flights)



}
case class RestartActor(mat: ActorMaterializer) extends Actor with ActorLogging {
  override def receive: Receive = {
    case ResetData =>
      log.info(s"About to shut down everything")

      mat.shutdown()
      log.info(s"Shutdown triggered")
  }
}
case object ResetData
