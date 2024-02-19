package uk.gov.homeoffice.drt.testsystem

import actors.{DrtParameters, FlightLookups, FlightLookupsLike, MinuteLookupsLike, PartitionedPortStateActor, StreamingJournalLike}
import akka.actor.{ActorRef, ActorSystem, Props}
import uk.gov.homeoffice.drt.crunchsystem.ReadRouteUpdateActorsLike
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.testsystem.TestActors.{QueueTestUpdatesSupervisor, StaffTestUpdatesSupervisor, TestFixedPointsActor, TestFlightUpdatesSupervisor, TestPartitionedPortStateActor, TestShiftsActor, TestStaffMovementsActor}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import scala.concurrent.ExecutionContext

case class TestActorService(journalType: StreamingJournalLike,
                            airportConfig: AirportConfig,
                            now: () => SDateLike,
                            params: DrtParameters,
                            flightLookups: FlightLookupsLike)(implicit system: ActorSystem, ec: ExecutionContext) extends ReadRouteUpdateActorsLike {

  override val minuteLookups: MinuteLookupsLike = TestMinuteLookups(system, now, MilliTimes.oneDayMillis, airportConfig.queuesByTerminal)

  override val liveShiftsReadActor: ActorRef = system.actorOf(TestShiftsActor.streamingUpdatesProps(
    journalType, airportConfig.minutesToCrunch, now), name = "shifts-read-actor")
  override val liveFixedPointsReadActor: ActorRef = system.actorOf(TestFixedPointsActor.streamingUpdatesProps(
    journalType, now, params.forecastMaxDays, airportConfig.minutesToCrunch), name = "fixed-points-read-actor")
  override val liveStaffMovementsReadActor: ActorRef = system.actorOf(TestStaffMovementsActor.streamingUpdatesProps(
    journalType, airportConfig.minutesToCrunch), name = "staff-movements-read-actor")

  override val flightsRouterActor: ActorRef = flightLookups.flightsRouterActor
  override val queueLoadsRouterActor: ActorRef = minuteLookups.queueLoadsMinutesActor
  override val queuesRouterActor: ActorRef = minuteLookups.queueMinutesRouterActor
  override val staffRouterActor: ActorRef = minuteLookups.staffMinutesRouterActor
  override val queueUpdates: ActorRef = system.actorOf(Props(
    new QueueTestUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      PartitionedPortStateActor.queueUpdatesProps(now, journalType)
    )),
    "updates-supervisor-queues")
  override val staffUpdates: ActorRef = system.actorOf(Props(
    new StaffTestUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      PartitionedPortStateActor.staffUpdatesProps(now, journalType)
    )
  ), "updates-supervisor-staff")
  override val flightUpdates: ActorRef = system.actorOf(Props(
    new TestFlightUpdatesSupervisor(
      now,
      airportConfig.queuesByTerminal.keys.toList,
      PartitionedPortStateActor.flightUpdatesProps(now, journalType)
    )
  ), "updates-supervisor-flight")

  override val portStateActor: ActorRef = system.actorOf(
    Props(
      new TestPartitionedPortStateActor(
        flightsRouterActor,
        queuesRouterActor,
        staffRouterActor,
        queueUpdates,
        staffUpdates,
        flightUpdates,
        now,
        airportConfig.queuesByTerminal,
        journalType
      )
    ),
    "port-state-actor"
  )

}
