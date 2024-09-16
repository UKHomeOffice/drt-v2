package uk.gov.homeoffice.drt.testsystem.crunchsystem

import actors.ManifestLookupsLike
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
  override val manifestsRouterActor: ActorRef =
    system.actorOf(Props(new TestVoyageManifestsActor(
      manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  override val mergeArrivalsQueueActor: ActorRef =
    system.actorOf(Props(new TestMergeArrivalsQueueActor(now = () => SDate.now())), "merge-arrivals-queue-actor")
  override val crunchQueueActor: ActorRef =
    system.actorOf(Props(new TestCrunchQueueActor(now = () => SDate.now())), "crunch-queue-actor")
  override val deskRecsQueueActor: ActorRef =
    system.actorOf(Props(new TestDeskRecsQueueActor(now = () => SDate.now())), "desk-recs-queue-actor")
  override val deploymentQueueActor: ActorRef =
    system.actorOf(Props(new TestDeploymentQueueActor(now = () => SDate.now())), "deployments-queue-actor")
  override val staffingQueueActor: ActorRef =
    system.actorOf(Props(new TestStaffingUpdateQueueActor(now = () => SDate.now())), "staffing-queue-actor")

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new MockAggregatedArrivalsActor()), "aggregated-arrivals-actor")
}
