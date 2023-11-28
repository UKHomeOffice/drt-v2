package uk.gov.homeoffice.drt.testsystem

import actors._
import actors.daily._
import actors.persistent._
import actors.persistent.arrivals.{AclForecastArrivalsActor, PortForecastArrivalsActor, PortLiveArrivalsActor}
import actors.persistent.staffing.{FixedPointsActorLike, ShiftsActorLike, StaffMovementsActorLike, StaffMovementsState}
import actors.routing.FlightsRouterActor
import actors.routing.minutes.MinutesActorLike._
import actors.routing.minutes._
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.StatusReply.Ack
import akka.pattern.{ask, pipe}
import akka.persistence.{DeleteMessagesSuccess, DeleteSnapshotsSuccess, PersistentActor, SnapshotSelectionCriteria}
import drt.shared.CrunchApi._
import drt.shared._
import org.slf4j.Logger
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, FlightsWithSplits, WithTimeAccessor}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.Future


object TestActors {

  case object ResetData

  trait Resettable extends PersistentActor {
    val log: Logger
    var replyTo: Option[ActorRef] = None
    private var deletedMessages: Boolean = false
    private var deletedSnapshots: Boolean = false

    def resetState(): Unit

    private def deletionFinished: Boolean = deletedMessages && deletedSnapshots

    def resetBehaviour: Receive = {
      case ResetData =>
        replyTo = Option(sender())
        log.warn("Received ResetData request. Deleting all messages & snapshots")
        deleteMessages(Long.MaxValue)
        deleteSnapshots(SnapshotSelectionCriteria(minSequenceNr = 0L, maxSequenceNr = Long.MaxValue))
      case _: DeleteMessagesSuccess =>
        deletedMessages = true
        ackIfDeletionFinished()
      case _: DeleteSnapshotsSuccess =>
        deletedSnapshots = true
        ackIfDeletionFinished()
    }

    private def ackIfDeletionFinished(): Unit = replyTo.foreach { r =>
      if (deletionFinished) {
        log.info("Finished deletions")
        resetState()
        deletedMessages = false
        deletedSnapshots = false
        replyTo = None
        r ! Ack
      }
    }
  }

  class TestAclForecastArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends AclForecastArrivalsActor(now, expireAfterMillis) with Resettable {
    override def resetState(): Unit = state = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestPortForecastArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends PortForecastArrivalsActor(now, expireAfterMillis) {

    private def resetBehaviour: Receive = {
      case ResetData =>
        state.clear()
        sender() ! Ack
    }

    override def receiveRecover: Receive = {
      case _ => ()
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestPortLiveArrivalsActor(override val now: () => SDateLike, expireAfterMillis: Int)
    extends PortLiveArrivalsActor(now, expireAfterMillis) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestCrunchQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int)
    extends CrunchQueueActor(now, crunchOffsetMinutes, durationMinutes) with Resettable {
    override def resetState(): Unit = {
      state.clear()
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestDeskRecsQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int)
    extends DeskRecsQueueActor(now, crunchOffsetMinutes, durationMinutes) with Resettable {
    override def resetState(): Unit = {
      state.clear()
    }

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestDeploymentQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int)
    extends DeploymentQueueActor(now, crunchOffsetMinutes, durationMinutes) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestStaffingUpdateQueueActor(now: () => SDateLike, crunchOffsetMinutes: Int, durationMinutes: Int)
    extends StaffingUpdateQueueActor(now, crunchOffsetMinutes, durationMinutes) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestVoyageManifestsActor(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate)
    extends ManifestRouterActor(manifestLookup, manifestsUpdate) with Resettable {

    override def resetState(): Unit = state = initialState

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestStreamingUpdatesActor[T, S](persistenceId: String,
                                        journalType: StreamingJournalLike,
                                        initialState: T,
                                        snapshotMessageToState: Any => T,
                                        eventToState: (T, Any) => (T, S),
                                        query: (() => T, () => ActorRef) => PartialFunction[Any, Unit],
                                       ) extends StreamingUpdatesActor[T, S](persistenceId, journalType, initialState, snapshotMessageToState, eventToState, query) {
    override val receiveQuery: Receive = query(() => state, sender) orElse {
      case ResetData =>
        maybeKillSwitch.foreach(_.shutdown())
        state = initialState
        println(s"\n\n** Resetting $persistenceId - streaming from $lastSequenceNr **\n\n")
        val killSwitch = startUpdatesStream(lastSequenceNr)
        maybeKillSwitch = Option(killSwitch)
    }
  }

  object TestShiftsActor extends ShiftsActorLike {
    override def streamingUpdatesProps(journalType: StreamingJournalLike,
                                       minutesToCrunch: Int,
                                       now: () => SDateLike,
                                      ): Props =
      Props(new TestStreamingUpdatesActor[ShiftAssignments, Iterable[TerminalUpdateRequest]](
        persistenceId,
        journalType,
        ShiftAssignments.empty,
        snapshotMessageToState,
        eventToState(now, minutesToCrunch),
        query(now)
      ))
  }

  object TestFixedPointsActor extends FixedPointsActorLike {

    import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal

    override def streamingUpdatesProps(journalType: StreamingJournalLike,
                                       now: () => SDateLike,
                                       forecastMaxDays: Int,
                                       minutesToCrunch: Int,
                                      ): Props =
      Props(new TestStreamingUpdatesActor[FixedPointAssignments, Iterable[TerminalUpdateRequest]](
        persistenceId,
        journalType,
        FixedPointAssignments.empty,
        snapshotMessageToState,
        eventToState(now, forecastMaxDays, minutesToCrunch),
        query(now)
      ))
  }

  object TestStaffMovementsActor extends StaffMovementsActorLike {

    override def streamingUpdatesProps(journalType: StreamingJournalLike, minutesToCrunch: Int): Props =
      Props(new TestStreamingUpdatesActor[StaffMovementsState, Iterable[TerminalUpdateRequest]](
        persistenceId,
        journalType,
        StaffMovementsState(StaffMovements(List())),
        snapshotMessageToState,
        eventToState(minutesToCrunch),
        query
      ))
  }

  class MockAggregatedArrivalsActor extends Actor {
    override def receive: Receive = {
      case _ => sender() ! Ack
    }
  }

  trait TestMinuteActorLike[A, B <: WithTimeAccessor] extends MinutesActorLike[A, B] {
    val resetData: (Terminal, MillisSinceEpoch) => Future[Any]
    private var terminalDaysUpdated: Set[(Terminal, MillisSinceEpoch)] = Set()

    private def addToTerminalDays(container: MinutesContainer[A, B]): Unit = {
      partitionUpdates(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, SDate(date).millisSinceEpoch))
      }
    }

    def resetReceive: Receive = {
      case container: MinutesContainer[A, B] =>
        addToTerminalDays(container)
        handleUpdatesAndAck(container, sender())

      case ResetData =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) => resetData(t, d) })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(sender())
    }

  }

  trait TestMinuteActorLike2[A, B <: WithTimeAccessor] extends MinutesActorLike2[A, B] {
    val resetData: (Terminal, MillisSinceEpoch) => Future[Any]
    private var terminalDaysUpdated: Set[(Terminal, MillisSinceEpoch)] = Set()

    private def addToTerminalDays(container: MinutesContainer[A, B]): Unit = {
      partitionUpdates(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, SDate(date).millisSinceEpoch))
      }
    }

    def resetReceive: Receive = {
      case container: MinutesContainer[A, B] =>
        addToTerminalDays(container)
        handleUpdatesAndAck(container, sender())

      case ResetData =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) => resetData(t, d) })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(sender())
    }

  }

  class TestStaffMinutesRouterActor(terminals: Iterable[Terminal],
                                    lookup: MinutesLookup[StaffMinute, TM],
                                    updateMinutes: MinutesUpdate[StaffMinute, TM],
                                    val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends StaffMinutesRouterActor(terminals, lookup, updateMinutes) with TestMinuteActorLike[StaffMinute, TM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueMinutesRouterActor(terminals: Iterable[Terminal],
                                    lookup: MinutesLookup[CrunchMinute, TQM],
                                    updateMinutes: MinutesUpdate[CrunchMinute, TQM],
                                    val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends QueueMinutesRouterActor(terminals, lookup, updateMinutes) with TestMinuteActorLike2[CrunchMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueLoadsMinutesActor(terminals: Iterable[Terminal],
                                   lookup: MinutesLookup[PassengersMinute, TQM],
                                   updateMinutes: MinutesUpdate[PassengersMinute, TQM],
                                   val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends QueueLoadsMinutesActor(terminals, lookup, updateMinutes) with TestMinuteActorLike2[PassengersMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class DummyActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }

  class TestFlightsRouterActor(terminals: Iterable[Terminal],
                               byDayLookup: FlightsLookup,
                               updateMinutes: FlightsUpdate,
                               val resetData: (Terminal, UtcDate) => Future[Any],
                               paxFeedSourceOrder: List[FeedSource])
    extends FlightsRouterActor(terminals, byDayLookup, updateMinutes, paxFeedSourceOrder) {
    override def receive: Receive = resetReceive orElse super.receive

    private var terminalDaysUpdated: Set[(Terminal, UtcDate)] = Set()

    private def addToTerminalDays(container: ArrivalsDiff): Unit = {
      partitionUpdates(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, date))
      }
    }

    private def resetReceive: Receive = {
      case container: ArrivalsDiff =>
        val replyTo = sender()
        addToTerminalDays(container)
        handleUpdatesAndAck(container, replyTo)

      case ResetData =>
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) =>
            resetData(t, d)
          })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(sender())
    }
  }

  class TestPartitionedPortStateActor(flightsActor: ActorRef,
                                      queuesActor: ActorRef,
                                      staffActor: ActorRef,
                                      queueUpdatesActor: ActorRef,
                                      staffUpdatesActor: ActorRef,
                                      flightUpdatesActor: ActorRef,
                                      now: () => SDateLike,
                                      queues: Map[Terminal, Seq[Queue]],
                                      journalType: StreamingJournalLike)
    extends PartitionedPortStateActor(
      flightsActor,
      queuesActor,
      staffActor,
      queueUpdatesActor,
      staffUpdatesActor,
      flightUpdatesActor,
      now,
      queues,
      journalType) {

    private val actorClearRequests = Map(
      flightsActor -> ResetData,
      queuesActor -> ResetData,
      staffActor -> ResetData,
      queueUpdatesActor -> PurgeAll,
      staffUpdatesActor -> PurgeAll,
      flightUpdatesActor -> PurgeAll
    )

    private def myReceive: Receive = {
      case ResetData =>
        Future
          .sequence(actorClearRequests.map {
            case (actor, request) => actor.ask(request)
          })
          .map(_ => Ack)
          .pipeTo(sender())
    }

    override def receive: Receive = myReceive orElse super.receive
  }

  class TestTerminalDayQueuesActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike) extends TerminalDayQueuesActor(year, month, day, terminal, now, None) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestTerminalDayStaffActor(year: Int,
                                  month: Int,
                                  day: Int,
                                  terminal: Terminal,
                                  now: () => SDateLike) extends TerminalDayStaffActor(year, month, day, terminal, now, None) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestTerminalDayFlightActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike,
                                   paxFeedSourceOrder: List[FeedSource],
                                  ) extends TerminalDayFlightActor(year, month, day, terminal, now, None, None, paxFeedSourceOrder) with Resettable {
    override def resetState(): Unit = state = FlightsWithSplits.empty

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class QueueTestUpdatesSupervisor(now: () => SDateLike,
                                   terminals: List[Terminal],
                                   updatesActorFactory: (Terminal, SDateLike) => Props)
    extends TestUpdatesSupervisor[CrunchMinute, TQM](now, terminals, updatesActorFactory)

  class StaffTestUpdatesSupervisor(now: () => SDateLike,
                                   terminals: List[Terminal],
                                   updatesActorFactory: (Terminal, SDateLike) => Props)
    extends TestUpdatesSupervisor[StaffMinute, TM](now, terminals, updatesActorFactory)

  abstract class TestUpdatesSupervisor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                                 terminals: List[Terminal],
                                                                 updatesActorFactory: (Terminal, SDateLike) => Props)
    extends UpdatesSupervisor(now, terminals, updatesActorFactory) {
    def testReceive: Receive = {
      case PurgeAll =>
        val replyTo = sender()
        log.info(s"Received PurgeAll")
        Future.sequence(streamingUpdateActors.values.map(actor => killActor.ask(Terminate(actor)))).foreach { _ =>
          streamingUpdateActors = Map()
          lastRequests = Map()
          replyTo ! Ack
        }
    }

    override def receive: Receive = testReceive orElse super.receive
  }

  class TestFlightUpdatesSupervisor(now: () => SDateLike,
                                    terminals: List[Terminal],
                                    updatesActorFactory: (Terminal, SDateLike) => Props)
    extends FlightUpdatesSupervisor(now, terminals, updatesActorFactory) {

    def testReceive: Receive = {
      case PurgeAll =>
        val replyTo = sender()
        log.info(s"Received PurgeAll")
        Future.sequence(streamingUpdateActors.values.map(actor => killActor.ask(Terminate(actor)))).foreach { _ =>
          streamingUpdateActors = Map()
          lastRequests = Map()
          replyTo ! Ack
        }
    }

    override def receive: Receive = testReceive orElse super.receive
  }

}
