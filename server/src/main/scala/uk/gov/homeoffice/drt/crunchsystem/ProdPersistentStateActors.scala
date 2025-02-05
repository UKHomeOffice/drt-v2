package uk.gov.homeoffice.drt.crunchsystem

import actors.ManifestLookups
import actors.persistent._
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDateLike


object ProdPersistentStateActors {
  def apply(system: ActorSystem,
            now: () => SDateLike,
            manifestLookups: ManifestLookups,
            terminals: Iterable[Terminal],
           ): PersistentStateActors = new PersistentStateActors {
    override val manifestsRouterActor: ActorRef =
      system.actorOf(
        Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

    override val mergeArrivalsQueueActor: ActorRef =
      system.actorOf(Props(new MergeArrivalsQueueActor(now, terminals)), "merge-arrivals-queue-actor")
    override val crunchQueueActor: ActorRef =
      system.actorOf(Props(new CrunchQueueActor(now, terminals)), "crunch-queue-actor")
    override val deskRecsQueueActor: ActorRef =
      system.actorOf(Props(new DeskRecsQueueActor(now, terminals)), "desk-recs-queue-actor")
    override val deploymentQueueActor: ActorRef =
      system.actorOf(Props(new DeploymentQueueActor(now, terminals)), "deployments-queue-actor")
    override val staffingQueueActor: ActorRef =
      system.actorOf(Props(new StaffingUpdateQueueActor(now, terminals)), "staffing-queue-actor")
  }
}
