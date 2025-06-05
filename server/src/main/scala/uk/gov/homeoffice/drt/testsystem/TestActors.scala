package uk.gov.homeoffice.drt.testsystem

import actors._
import actors.daily.StreamingUpdatesLike.StopUpdates
import actors.daily._
import actors.persistent._
import actors.persistent.staffing.{FixedPointsActorLike, ShiftsActorLike, StaffMovementsActorLike, StaffMovementsState}
import actors.routing.FeedArrivalsRouterActor.FeedArrivals
import actors.routing.minutes.MinutesActorLike._
import actors.routing.minutes._
import actors.routing.{FeedArrivalsRouterActor, FlightsRouterActor}
import drt.shared.CrunchApi._
import drt.shared._
import org.apache.pekko.actor.{Actor, ActorRef, Props}
import org.apache.pekko.pattern.StatusReply.Ack
import org.apache.pekko.pattern.{ask, pipe}
import org.apache.pekko.persistence.{DeleteMessagesSuccess, DeleteSnapshotsSuccess, PersistentActor, SnapshotSelectionCriteria}
import org.slf4j.Logger
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.models.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, Terminals}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.concurrent.Future


object TestActors {

  case object ResetData

  trait Resettable extends PersistentActor {
    protected val log: Logger
    var replyTo: Option[ActorRef] = None
    private var deletedMessages: Boolean = false
    private var deletedSnapshots: Boolean = false

    def resetState(): Unit

    private def deletionFinished: Boolean = deletedMessages && deletedSnapshots

    def resetBehaviour: Receive = {
      case ResetData =>
        replyTo = Option(sender())
        log.warn("Received ResetData request. Deleting all messages & snapshots")
        resetState()
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
        log.info("Finished message & snapshot deletions")
        resetState()
        deletedMessages = false
        deletedSnapshots = false
        replyTo = None
        r ! Ack
      }
    }
  }

  class TestMergeArrivalsQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal])
    extends CrunchQueueActor(now, terminals) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestCrunchQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal])
    extends CrunchQueueActor(now, terminals) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestDeskRecsQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal])
    extends DeskRecsQueueActor(now, terminals) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestDeploymentQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal])
    extends DeploymentQueueActor(now, terminals) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestStaffingUpdateQueueActor(now: () => SDateLike, terminals: LocalDate => Iterable[Terminal])
    extends StaffingUpdateQueueActor(now, terminals) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestVoyageManifestsActor(manifestLookup: ManifestLookup, manifestsUpdate: ManifestsUpdate)
    extends ManifestRouterActor(manifestLookup, manifestsUpdate) with Resettable {

    override def resetState(): Unit = state = initialState

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  private class TestStreamingUpdatesActor[T, S](persistenceId: String,
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
        val killSwitch = startUpdatesStream(lastSequenceNr)
        maybeKillSwitch = Option(killSwitch)
        sender() ! Ack
    }
  }

  object TestShiftsActor extends ShiftsActorLike {
    override def streamingUpdatesProps(persistenceId: String,
                                       journalType: StreamingJournalLike,
                                       now: () => SDateLike,
                                      ): Props =
      Props(new TestStreamingUpdatesActor[ShiftAssignments, Iterable[TerminalUpdateRequest]](
        persistenceId,
        journalType,
        ShiftAssignments.empty,
        snapshotMessageToState,
        eventToState(now),
        query(now)
      ))

    override def persistenceId: String = ???
  }

  object TestFixedPointsActor extends FixedPointsActorLike {

    import uk.gov.homeoffice.drt.time.SDate.implicits.sdateFromMillisLocal

    override def streamingUpdatesProps(journalType: StreamingJournalLike,
                                       now: () => SDateLike,
                                       forecastMaxDays: Int,
                                      ): Props =
      Props(new TestStreamingUpdatesActor[FixedPointAssignments, Iterable[TerminalUpdateRequest]](
        persistenceId,
        journalType,
        FixedPointAssignments.empty,
        snapshotMessageToState,
        eventToState(now, forecastMaxDays),
        query
      ))
  }

  object TestStaffMovementsActor extends StaffMovementsActorLike {

    override def streamingUpdatesProps(journalType: StreamingJournalLike): Props =
      Props(new TestStreamingUpdatesActor[StaffMovementsState, Iterable[TerminalUpdateRequest]](
        persistenceId,
        journalType,
        StaffMovementsState(StaffMovements(List())),
        snapshotMessageToState,
        eventToState,
        query
      ))
  }

//  class MockAggregatedArrivalsActor extends Actor {
//    override def receive: Receive = {
//      case _ => sender() ! Ack
//    }
//  }

  trait TestMinuteActorLike[A, B <: WithTimeAccessor] extends MinutesActorLike[A, B, TerminalUpdateRequest] {
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

  trait TestMinuteActorLike2[A, B <: WithTimeAccessor] extends MinutesActorLike2[A, B, TerminalUpdateRequest] {
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

  class TestStaffMinutesRouterActor(terminalsForDateRange: (LocalDate, LocalDate) => Seq[Terminal],
                                    lookup: MinutesLookup[StaffMinute, TM],
                                    updateMinutes: MinutesUpdate[StaffMinute, TM, TerminalUpdateRequest],
                                    val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends StaffMinutesRouterActor(terminalsForDateRange, lookup, updateMinutes) with TestMinuteActorLike[StaffMinute, TM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueMinutesRouterActor(terminalsForDateRange: (LocalDate, LocalDate) => Seq[Terminal],
                                    lookup: MinutesLookup[CrunchMinute, TQM],
                                    updateMinutes: MinutesUpdate[CrunchMinute, TQM, TerminalUpdateRequest],
                                    val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends QueueMinutesRouterActor(terminalsForDateRange, lookup, updateMinutes) with TestMinuteActorLike2[CrunchMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class TestQueueLoadsMinutesActor(terminalsForDateRange: (LocalDate, LocalDate) => Seq[Terminal],
                                   lookup: MinutesLookup[PassengersMinute, TQM],
                                   updateMinutes: MinutesUpdate[PassengersMinute, TQM, TerminalUpdateRequest],
                                   val resetData: (Terminal, MillisSinceEpoch) => Future[Any])
    extends QueueLoadsMinutesActor(terminalsForDateRange, lookup, updateMinutes) with TestMinuteActorLike2[PassengersMinute, TQM] {
    override def receive: Receive = resetReceive orElse super.receive
  }

  class DummyActor extends Actor {
    override def receive: Receive = {
      case _ =>
    }
  }

  class TestFlightsRouterActor(terminalsForDateRange: (LocalDate, LocalDate) => Seq[Terminal],
                               byDayLookup: FlightsLookup,
                               updateMinutes: (Option[ActorRef], Option[ActorRef]) => FlightsUpdate,
                               resetData: (Terminal, UtcDate) => Future[Any],
                               paxFeedSourceOrder: List[FeedSource])
    extends FlightsRouterActor(terminalsForDateRange, byDayLookup, updateMinutes, paxFeedSourceOrder) {
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
        val replyTo = sender()
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) =>
            resetData(t, d)
          })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(replyTo)
    }
  }

  class TestFeedArrivalsRouterActor(allTerminals: (LocalDate, LocalDate) => Iterable[Terminal],
                                    arrivalsByDayLookup: Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]],
                                    updateArrivals: ((Terminals.Terminal, UtcDate), Seq[FeedArrival]) => Future[Boolean],
                                    partitionUpdates: PartialFunction[FeedArrivals, Map[(Terminal, UtcDate), FeedArrivals]],
                                    resetData: (Terminal, UtcDate) => Future[Any],
                                   )
    extends FeedArrivalsRouterActor(allTerminals, arrivalsByDayLookup, updateArrivals, partitionUpdates) {
    override def receive: Receive = resetReceive orElse super.receive

    private var terminalDaysUpdated: Set[(Terminal, UtcDate)] = Set()

    private def addToTerminalDays(container: FeedArrivals): Unit = {
      partitionUpdates(container).keys.foreach {
        case (terminal, date) => terminalDaysUpdated = terminalDaysUpdated + ((terminal, date))
      }
    }

    private def resetReceive: Receive = {
      case container: FeedArrivals =>
        val replyTo = sender()
        addToTerminalDays(container)
        handleUpdatesAndAck(container, replyTo)

      case ResetData =>
        val replyTo = sender()
        Future
          .sequence(terminalDaysUpdated.map { case (t, d) =>
            resetData(t, d)
          })
          .map { _ =>
            terminalDaysUpdated = Set()
            Ack
          }
          .pipeTo(replyTo)
    }
  }

  class TestPartitionedPortStateActor(flightsActor: ActorRef,
                                      queuesActor: ActorRef,
                                      staffActor: ActorRef,
                                      queueUpdatesActor: ActorRef,
                                      staffUpdatesActor: ActorRef,
                                      flightUpdatesActor: ActorRef,
                                      now: () => SDateLike,
                                      journalType: StreamingJournalLike)
    extends PartitionedPortStateActor(
      flightsActor,
      queuesActor,
      staffActor,
      queueUpdatesActor,
      staffUpdatesActor,
      flightUpdatesActor,
      now,
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

  class TestTerminalDayQueuesActor(date: UtcDate,
                                   terminal: Terminal,
                                   queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue],
                                   now: () => SDateLike,
                                   onUpdate: Option[(UtcDate, Iterable[CrunchMinute]) => Future[Unit]],
                                  ) extends TerminalDayQueuesActor(date, terminal, queuesForDateAndTerminal, now, None, onUpdate) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestTerminalDayStaffActor(year: Int,
                                  month: Int,
                                  day: Int,
                                  terminal: Terminal,
                                  now: () => SDateLike,
                                 ) extends TerminalDayStaffActor(year, month, day, terminal, now, None) with Resettable {
    override def resetState(): Unit = state.clear()

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class TestTerminalDayFlightActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike,
                                   paxFeedSourceOrder: List[FeedSource],
                                   requestHistoricSplitsActor: Option[ActorRef],
                                   requestHistoricPaxActor: Option[ActorRef],
                                   maybeUpdateLiveView: Option[(Iterable[ApiFlightWithSplits], Iterable[UniqueArrival]) => Future[Unit]],
                                  )
    extends TerminalDayFlightActor(
      year,
      month,
      day,
      terminal,
      now,
      None,
      None,
      paxFeedSourceOrder,
      None,
      requestHistoricSplitsActor,
      requestHistoricPaxActor,
      maybeUpdateLiveView,
    ) with Resettable {
    override def resetState(): Unit = state = FlightsWithSplits.empty

    override def receiveCommand: Receive = resetBehaviour orElse super.receiveCommand
  }

  class QueueTestUpdatesSupervisor(now: () => SDateLike,
                                   terminals: LocalDate => Seq[Terminal],
                                   updatesActorFactory: (Terminal, SDateLike) => Props)
    extends TestUpdatesSupervisor[CrunchMinute, TQM](now, terminals, updatesActorFactory)

  class StaffTestUpdatesSupervisor(now: () => SDateLike,
                                   terminals: LocalDate => Seq[Terminal],
                                   updatesActorFactory: (Terminal, SDateLike) => Props)
    extends TestUpdatesSupervisor[StaffMinute, TM](now, terminals, updatesActorFactory)

  abstract class TestUpdatesSupervisor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                                 terminals: LocalDate => Seq[Terminal],
                                                                 updatesActorFactory: (Terminal, SDateLike) => Props)
    extends UpdatesSupervisor(now, terminals, updatesActorFactory) {
    def testReceive: Receive = {
      case PurgeAll =>
        val replyTo = sender()
        log.info(s"Received PurgeAll")
        streamingUpdateActors.values.foreach(_ ! StopUpdates)
        streamingUpdateActors = Map()
        lastRequests = Map()
        replyTo ! Ack
    }

    override def receive: Receive = testReceive orElse super.receive
  }

  class TestFlightUpdatesSupervisor(now: () => SDateLike,
                                    terminals: LocalDate => Seq[Terminal],
                                    updatesActorFactory: (Terminal, SDateLike) => Props)
    extends FlightUpdatesSupervisor(now, terminals, updatesActorFactory) {

    def testReceive: Receive = {
      case PurgeAll =>
        val replyTo = sender()
        log.info(s"Received PurgeAll")
        streamingUpdateActors.values.foreach(_ ! StopUpdates)
        streamingUpdateActors = Map()
        lastRequests = Map()
        replyTo ! Ack
    }

    override def receive: Receive = testReceive orElse super.receive
  }

}
