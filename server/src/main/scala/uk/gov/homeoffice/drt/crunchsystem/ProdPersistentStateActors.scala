package uk.gov.homeoffice.drt.crunchsystem

import actors.DrtStaticParameters.expireAfterMillis
import actors.persistent._
import actors.persistent.arrivals.{AclForecastArrivalsActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
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
    override val forecastBaseArrivalsActor: ActorRef =
      system.actorOf(Props(new AclForecastArrivalsActor(now, expireAfterMillis)), name = "base-arrivals-actor")
    override val forecastArrivalsActor: ActorRef =
      system.actorOf(Props(new PortForecastArrivalsActor(now, expireAfterMillis)), name = "forecast-arrivals-actor")
    override val liveArrivalsActor: ActorRef =
      system.actorOf(Props(new PortLiveArrivalsActor(now, expireAfterMillis)), name = "live-arrivals-actor")
    override val liveBaseArrivalsActor: ActorRef =
      system.actorOf(Props(new CirriumLiveArrivalsActor(now, expireAfterMillis)), name = "live-base-arrivals-actor")
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
