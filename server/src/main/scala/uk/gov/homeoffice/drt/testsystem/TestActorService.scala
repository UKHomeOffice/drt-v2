package uk.gov.homeoffice.drt.testsystem

import actors.DrtStaticParameters.{startOfTheMonth, time48HoursAgo}
import actors._
import actors.daily.RequestAndTerminateActor
import actors.persistent.staffing.{FixedPointsActor, LegacyShiftAssignmentsActor, ShiftAssignmentsActor, StaffMovementsActor}
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.util.Timeout
import uk.gov.homeoffice.drt.crunchsystem.ActorsServiceLike
import uk.gov.homeoffice.drt.ports.{AirportConfig, Terminals}
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

case class TestActorService(journalType: StreamingJournalLike,
                            airportConfig: AirportConfig,
                            now: () => SDateLike,
                            forecastMaxDays: Int,
                            flightLookups: FlightLookupsLike,
                            minuteLookups: MinuteLookupsLike,
                           )
                           (implicit system: ActorSystem, timeout: Timeout) extends ActorsServiceLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor), "request-and-terminate-actor")
  override val legacyShiftAssignmentsReadActor: ActorRef = system.actorOf(TestShiftsActor.streamingUpdatesProps(LegacyShiftAssignmentsActor.persistenceId,
    journalType, now), name = "shifts-read-actor")
  override val liveShiftAssignmentsReadActor: ActorRef = system.actorOf(TestShiftsActor.streamingUpdatesProps(ShiftAssignmentsActor.persistenceId,
    journalType, now), name = "staff-assignments-read-actor")
  override val liveFixedPointsReadActor: ActorRef = system.actorOf(TestFixedPointsActor.streamingUpdatesProps(
    journalType, now, forecastMaxDays), name = "fixed-points-read-actor")
  override val liveStaffMovementsReadActor: ActorRef = system.actorOf(TestStaffMovementsActor.streamingUpdatesProps(
    journalType), name = "staff-movements-read-actor")

  override val legacyShiftAssignmentsSequentialWritesActor: ActorRef = system.actorOf(LegacyShiftAssignmentsActor.sequentialWritesProps(
    now, startOfTheMonth(now), requestAndTerminateActor, system), "shifts-sequential-writes-actor")
  override val shiftAssignmentsSequentialWritesActor: ActorRef = system.actorOf(ShiftAssignmentsActor.sequentialWritesProps(
    now, startOfTheMonth(now), requestAndTerminateActor, system), "staff-assignments-sequential-writes-actor")

  override val fixedPointsSequentialWritesActor: ActorRef = system.actorOf(FixedPointsActor.sequentialWritesProps(
    now, requestAndTerminateActor, system), "fixed-points-sequential-writes-actor")
  override val staffMovementsSequentialWritesActor: ActorRef = system.actorOf(StaffMovementsActor.sequentialWritesProps(
    now, time48HoursAgo(now), requestAndTerminateActor, system), "staff-movements-sequential-writes-actor")

  override val flightsRouterActor: ActorRef = flightLookups.flightsRouterActor
  override val queueLoadsRouterActor: ActorRef = minuteLookups.queueLoadsMinutesActor
  override val queuesRouterActor: ActorRef = minuteLookups.queueMinutesRouterActor
  override val staffRouterActor: ActorRef = minuteLookups.staffMinutesRouterActor

  val terminals: LocalDate => Seq[Terminals.Terminal] = QueueConfig.terminalsForDate(airportConfig.queuesByTerminal)

  override val queueUpdates: ActorRef = system.actorOf(Props(
    new QueueTestUpdatesSupervisor(
      now = now,
      terminals = terminals,
      updatesActorFactory = PartitionedPortStateActor.queueUpdatesProps(now, journalType)
    )),
    "updates-supervisor-queues")
  override val staffUpdates: ActorRef = system.actorOf(Props(
    new StaffTestUpdatesSupervisor(
      now = now,
      terminals = terminals,
      updatesActorFactory = PartitionedPortStateActor.staffUpdatesProps(now, journalType)
    )
  ), "updates-supervisor-staff")
  override val flightUpdates: ActorRef = system.actorOf(Props(
    new TestFlightUpdatesSupervisor(
      now = now,
      terminals = terminals,
      updatesActorFactory = PartitionedPortStateActor.flightUpdatesProps(now, journalType)
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
        journalType
      )
    ),
    "port-state-actor"
  )
}
