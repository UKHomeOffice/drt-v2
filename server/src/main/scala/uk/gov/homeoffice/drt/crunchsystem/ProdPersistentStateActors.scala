package uk.gov.homeoffice.drt.crunchsystem

import actors.DrtStaticParameters.{expireAfterMillis, time48HoursAgo, startOfTheMonth}
import actors.persistent.arrivals.{AclForecastArrivalsActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import actors.persistent._
import actors.{AggregatedArrivalsActor, ManifestLookups}
import akka.actor.{ActorRef, ActorSystem, Props}
import slickdb.ArrivalTable
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

    override val crunchQueueActor: ActorRef =
      system.actorOf(Props(new CrunchQueueActor(now, offsetMinutes, minutesToCrunch)), "crunch-queue-actor")
    override val deskRecsQueueActor: ActorRef =
      system.actorOf(Props(new DeskRecsQueueActor(now, offsetMinutes, minutesToCrunch)), "desk-recs-queue-actor")
    override val deploymentQueueActor: ActorRef =
      system.actorOf(Props(new DeploymentQueueActor(now, offsetMinutes, minutesToCrunch)), "deployments-queue-actor")
    override val staffingQueueActor: ActorRef =
      system.actorOf(Props(new StaffingUpdateQueueActor(now, offsetMinutes, minutesToCrunch)), "staffing-queue-actor")

    override val aggregatedArrivalsActor: ActorRef =
      system.actorOf(Props(new AggregatedArrivalsActor(ArrivalTable(portCode, AggregateDb, paxFeedSourceOrder))), name = "aggregated-arrivals-actor")
  }
}
