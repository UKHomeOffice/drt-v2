package uk.gov.homeoffice.drt.testsystem.crunchsystem

import actors.ManifestLookupsLike
import akka.actor.{ActorRef, ActorSystem, Props}
import uk.gov.homeoffice.drt.crunchsystem.PersistentStateActors
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

case class TestPersistentStateActors(system: ActorSystem,
                                     now: () => SDateLike,
                                     minutesToCrunch: Int,
                                     offsetMinutes: Int,
                                     manifestLookups: ManifestLookupsLike,
                                     terminals: Iterable[Terminal],
                           ) extends PersistentStateActors {
  override val manifestsRouterActor: ActorRef =
    system.actorOf(Props(new TestVoyageManifestsActor(
      manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  override val mergeArrivalsQueueActor: ActorRef =
    system.actorOf(Props(new TestMergeArrivalsQueueActor(now = () => SDate.now(), terminals)), "merge-arrivals-queue-actor")
  override val crunchQueueActor: ActorRef =
    system.actorOf(Props(new TestCrunchQueueActor(now = () => SDate.now(), terminals)), "crunch-queue-actor")
  override val deskRecsQueueActor: ActorRef =
    system.actorOf(Props(new TestDeskRecsQueueActor(now = () => SDate.now(), terminals)), "desk-recs-queue-actor")
  override val deploymentQueueActor: ActorRef =
    system.actorOf(Props(new TestDeploymentQueueActor(now = () => SDate.now(), terminals)), "deployments-queue-actor")
  override val staffingQueueActor: ActorRef =
    system.actorOf(Props(new TestStaffingUpdateQueueActor(now = () => SDate.now(), terminals)), "staffing-queue-actor")

  override val aggregatedArrivalsActor: ActorRef = system.actorOf(Props(new MockAggregatedArrivalsActor()), "aggregated-arrivals-actor")
}
