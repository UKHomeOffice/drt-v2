package uk.gov.homeoffice.drt.crunchsystem

import actors.persistent._
import actors.{AggregatedArrivalsActor, ManifestLookups}
import akka.actor.{ActorRef, ActorSystem, Props}
import drt.shared.CrunchApi.MillisSinceEpoch
import slickdb.ArrivalTable
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, MergeArrivalsRequest}
import uk.gov.homeoffice.drt.db.AggregateDb
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}


object ProdPersistentStateActors {
  def apply(system: ActorSystem,
            now: () => SDateLike,
            minutesToCrunch: Int,
            offsetMinutes: Int,
            manifestLookups: ManifestLookups,
            portCode: PortCode,
            paxFeedSourceOrder: List[FeedSource],
           ): PersistentStateActors = new PersistentStateActors {
    override val manifestsRouterActor: ActorRef =
      system.actorOf(
        Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

    val crunchRequest: MillisSinceEpoch => CrunchRequest =
      (millis: MillisSinceEpoch) => CrunchRequest(millis, offsetMinutes, minutesToCrunch)

    val mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest =
      (millis: MillisSinceEpoch) => MergeArrivalsRequest(SDate(millis).toUtcDate)

    override val mergeArrivalsQueueActor: ActorRef =
      system.actorOf(Props(new MergeArrivalsQueueActor(now, mergeArrivalRequest)), "merge-arrivals-queue-actor")
    override val crunchQueueActor: ActorRef =
      system.actorOf(Props(new CrunchQueueActor(now, crunchRequest)), "crunch-queue-actor")
    override val deskRecsQueueActor: ActorRef =
      system.actorOf(Props(new DeskRecsQueueActor(now, crunchRequest)), "desk-recs-queue-actor")
    override val deploymentQueueActor: ActorRef =
      system.actorOf(Props(new DeploymentQueueActor(now, crunchRequest)), "deployments-queue-actor")
    override val staffingQueueActor: ActorRef =
      system.actorOf(Props(new StaffingUpdateQueueActor(now, crunchRequest)), "staffing-queue-actor")

    override val aggregatedArrivalsActor: ActorRef =
      system.actorOf(Props(new AggregatedArrivalsActor(ArrivalTable(portCode, AggregateDb, paxFeedSourceOrder))), name = "aggregated-arrivals-actor")
  }
}
