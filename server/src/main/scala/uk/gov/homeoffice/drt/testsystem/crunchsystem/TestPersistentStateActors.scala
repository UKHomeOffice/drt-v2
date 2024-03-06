package uk.gov.homeoffice.drt.testsystem.crunchsystem

import actors.DrtStaticParameters.expireAfterMillis
import actors.ManifestLookupsLike
import actors.persistent.arrivals.CirriumLiveArrivalsActor
import akka.actor.{ActorRef, ActorSystem, Props}
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, MergeArrivalsRequest}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

case class TestPersistentStateActors(system: ActorSystem,
                                     now: () => SDateLike,
                                     minutesToCrunch: Int,
                                     offsetMinutes: Int,
                                     manifestLookups: ManifestLookupsLike,
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

  val crunchRequest: MillisSinceEpoch => CrunchRequest =
    (millis: MillisSinceEpoch) => CrunchRequest(millis, offsetMinutes, minutesToCrunch)

  val mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest =
    (millis: MillisSinceEpoch) => MergeArrivalsRequest(SDate(millis).toUtcDate)

  override val mergeArrivalsQueueActor: ActorRef =
    system.actorOf(Props(new TestMergeArrivalsQueueActor(now = () => SDate.now(), mergeArrivalRequest)), "merge-arrivals-queue-actor")
  override val crunchQueueActor: ActorRef =
    system.actorOf(Props(new TestCrunchQueueActor(now = () => SDate.now(), crunchRequest)), "crunch-queue-actor")
  override val deskRecsQueueActor: ActorRef =
    system.actorOf(Props(new TestDeskRecsQueueActor(now = () => SDate.now(), crunchRequest)), "desk-recs-queue-actor")
  override val deploymentQueueActor: ActorRef =
    system.actorOf(Props(new TestDeploymentQueueActor(now = () => SDate.now(), crunchRequest)), "deployments-queue-actor")
  override val staffingQueueActor: ActorRef =
    system.actorOf(Props(new TestStaffingUpdateQueueActor(now = () => SDate.now(), crunchRequest)), "staffing-queue-actor")

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new MockAggregatedArrivalsActor()), "aggregated-arrivals-actor")
}
