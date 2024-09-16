package uk.gov.homeoffice.drt.crunchsystem

import actors.persistent._
import actors.{AggregatedArrivalsActor, ManifestLookups}
import akka.actor.{ActorRef, ActorSystem, Props}
import slickdb.ArrivalTable
import uk.gov.homeoffice.drt.db.AggregateDb
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike


object ProdPersistentStateActors {
  def apply(system: ActorSystem,
            now: () => SDateLike,
            manifestLookups: ManifestLookups,
            portCode: PortCode,
            paxFeedSourceOrder: List[FeedSource],
           ): PersistentStateActors = new PersistentStateActors {
    override val manifestsRouterActor: ActorRef =
      system.actorOf(
        Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

    override val mergeArrivalsQueueActor: ActorRef =
      system.actorOf(Props(new MergeArrivalsQueueActor(now)), "merge-arrivals-queue-actor")
    override val crunchQueueActor: ActorRef =
      system.actorOf(Props(new CrunchQueueActor(now)), "crunch-queue-actor")
    override val deskRecsQueueActor: ActorRef =
      system.actorOf(Props(new DeskRecsQueueActor(now)), "desk-recs-queue-actor")
    override val deploymentQueueActor: ActorRef =
      system.actorOf(Props(new DeploymentQueueActor(now)), "deployments-queue-actor")
    override val staffingQueueActor: ActorRef =
      system.actorOf(Props(new StaffingUpdateQueueActor(now)), "staffing-queue-actor")

    override val aggregatedArrivalsActor: ActorRef =
      system.actorOf(Props(new AggregatedArrivalsActor(ArrivalTable(portCode, AggregateDb, paxFeedSourceOrder))), name = "aggregated-arrivals-actor")
  }
}
