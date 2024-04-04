package uk.gov.homeoffice.drt.service

import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}
import actors._
import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, RequestAndTerminateActor, StaffUpdatesSupervisor}
import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.util.Timeout
import uk.gov.homeoffice.drt.crunchsystem.ActorsServiceLike
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.time.SDateLike

import javax.inject.Singleton

@Singleton
case class ActorsServiceService(journalType: StreamingJournalLike,
                                airportConfig: AirportConfig,
                                now: () => SDateLike,
                                forecastMaxDays: Int,
                                flightLookups: FlightLookupsLike,
                                minuteLookups: MinuteLookupsLike,
                               )
                               (implicit system: ActorSystem, timeout: Timeout) extends ActorsServiceLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor), "request-and-terminate-actor")
  override val liveShiftsReadActor: ActorRef = system.actorOf(ShiftsActor.streamingUpdatesProps(
    journalType, airportConfig.minutesToCrunch, now), name = "shifts-read-actor")
  override val liveFixedPointsReadActor: ActorRef = system.actorOf(FixedPointsActor.streamingUpdatesProps(
    journalType, now, forecastMaxDays, airportConfig.minutesToCrunch), name = "fixed-points-read-actor")
  override val liveStaffMovementsReadActor: ActorRef = system.actorOf(StaffMovementsActor.streamingUpdatesProps(
    journalType, airportConfig.minutesToCrunch), name = "staff-movements-read-actor")

  override val flightsRouterActor: ActorRef = flightLookups.flightsRouterActor
  override val queueLoadsRouterActor: ActorRef = minuteLookups.queueLoadsMinutesActor
  override val queuesRouterActor: ActorRef = minuteLookups.queueMinutesRouterActor
  override val staffRouterActor: ActorRef = minuteLookups.staffMinutesRouterActor
  override val queueUpdates: ActorRef =
    system.actorOf(Props(new QueueUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      queueUpdatesProps(now, journalType))), "updates-supervisor-queues")
  override val staffUpdates: ActorRef =
    system.actorOf(Props(new StaffUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      staffUpdatesProps(now, journalType))), "updates-supervisor-staff")
  override val flightUpdates: ActorRef =
    system.actorOf(Props(new FlightUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      flightUpdatesProps(now, journalType))), "updates-supervisor-flights")

  override val portStateActor: ActorRef = system.actorOf(Props(new PartitionedPortStateActor(
    flightsRouterActor = flightsRouterActor,
    queuesRouterActor = queuesRouterActor,
    staffRouterActor = staffRouterActor,
    queueUpdatesActor = queueUpdates,
    staffUpdatesActor = staffUpdates,
    flightUpdatesActor = flightUpdates,
    now = now,
    queues = airportConfig.queuesByTerminal,
    journalType = journalType)))
}
