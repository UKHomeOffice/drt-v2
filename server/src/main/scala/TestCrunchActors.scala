import actors.DrtStaticParameters.{expireAfterMillis, time48HoursAgo, timeBeforeThisMonth}
import actors.ManifestLookups
import actors.persistent.arrivals.{AclForecastArrivalsActor, CirriumLiveArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import actors.persistent._
import akka.actor.{ActorRef, ActorSystem, Props}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

case class TestCrunchActors(system: ActorSystem,
                            now: () => SDateLike,
                            minutesToCrunch: Int,
                            offsetMinutes: Int,
                            maxForecastDays: Int,
                            manifestLookups: ManifestLookups,
                           ) extends CrunchActors {
  val shiftsActor: ActorRef =
    system.actorOf(Props(new ShiftsActor(now, timeBeforeThisMonth(now))), "staff-shifts")
  val fixedPointsActor: ActorRef =
    system.actorOf(Props(new FixedPointsActor(now, minutesToCrunch, maxForecastDays)), "staff-fixed-points")
  val staffMovementsActor: ActorRef =
    system.actorOf(Props(new StaffMovementsActor(now, time48HoursAgo(now), minutesToCrunch)), "staff-movements")

  val forecastBaseArrivalsActor: ActorRef =
    system.actorOf(Props(new AclForecastArrivalsActor(now, expireAfterMillis)), name = "base-arrivals-actor")
  val forecastArrivalsActor: ActorRef =
    system.actorOf(Props(new PortForecastArrivalsActor(now, expireAfterMillis)), name = "forecast-arrivals-actor")
  val liveArrivalsActor: ActorRef =
    system.actorOf(Props(new PortLiveArrivalsActor(now, expireAfterMillis)), name = "live-arrivals-actor")
  val liveBaseArrivalsActor: ActorRef =
    system.actorOf(Props(new CirriumLiveArrivalsActor(now, expireAfterMillis)), name = "live-base-arrivals-actor")
  val manifestsRouterActor: ActorRef =
    system.actorOf(Props(new ManifestRouterActor(manifestLookups.manifestsByDayLookup, manifestLookups.updateManifests)), name = "voyage-manifests-router-actor")

  val crunchQueueActor: ActorRef =
    system.actorOf(Props(new CrunchQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "crunch-queue-actor")
  val deskRecsQueueActor: ActorRef =
    system.actorOf(Props(new DeskRecsQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "desk-recs-queue-actor")
  val deploymentQueueActor: ActorRef =
    system.actorOf(Props(new DeploymentQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "deployments-queue-actor")
  val staffingQueueActor: ActorRef =
    system.actorOf(Props(new StaffingUpdateQueueActor(now = () => SDate.now(), offsetMinutes, minutesToCrunch)), "staffing-queue-actor")
}
