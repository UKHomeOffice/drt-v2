package uk.gov.homeoffice.drt.testsystem.crunchsystem

import actors.DrtStaticParameters.{expireAfterMillis, time48HoursAgo, timeBeforeThisMonth}
import actors.ManifestLookups
import actors.persistent.arrivals.CirriumLiveArrivalsActor
import akka.actor.{ActorRef, ActorSystem, Props}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

case class TestPersistentStateActors(system: ActorSystem,
                                     now: () => SDateLike,
                                     minutesToCrunch: Int,
                                     offsetMinutes: Int,
                                     maxForecastDays: Int,
                                     manifestLookups: ManifestLookups,
                           ) extends PersistentStateActors {
  override val forecastBaseArrivalsActor: ActorRef =
    system.actorOf(Props(new TestAclForecastArrivalsActor(now, expireAfterMillis)), name = "base-arrivals-actor")
  override val forecastArrivalsActor: ActorRef =
    system.actorOf(Props(new TestPortForecastArrivalsActor(now, expireAfterMillis)), name = "forecast-arrivals-actor")
  override val liveArrivalsActor: ActorRef =
    system.actorOf(Props(new TestPortLiveArrivalsActor(now, expireAfterMillis)), name = "live-arrivals-actor")
  override val liveBaseArrivalsActor: ActorRef =
    system.actorOf(Props(new CirriumLiveArrivalsActor(now, expireAfterMillis)), name = "live-base-arrivals-actor")
  override val manifestsRouterActor: ActorRef =
    system.actorOf(Props(new TestVoyageManifestsActor(
      manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  override val crunchQueueActor: ActorRef =
    system.actorOf(Props(new TestCrunchQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "crunch-queue-actor")
  override val deskRecsQueueActor: ActorRef =
    system.actorOf(Props(new TestDeskRecsQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "desk-recs-queue-actor")
  override val deploymentQueueActor: ActorRef =
    system.actorOf(Props(new TestDeploymentQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "deployments-queue-actor")
  override val staffingQueueActor: ActorRef =
    system.actorOf(Props(new TestStaffingUpdateQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "staffing-queue-actor")

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new MockAggregatedArrivalsActor()), "aggregated-arrivals-actor")
}
