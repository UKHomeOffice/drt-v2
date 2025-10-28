package actors.persistent.staffing

import actors.DrtStaticParameters.startOfTheMonth
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.persistent.StreamingUpdatesActor
import actors.persistent.staffing.LegacyShiftAssignmentsActor.{ReplaceAllShifts, UpdateShifts}
import actors.persistent.staffing.ShiftAssignmentsMessageParser.shiftMessagesToShiftAssignments
import actors.{ExpiryActorLike, StreamingJournalLike}
import drt.shared._
import org.apache.pekko.actor.{ActorRef, Props, Scheduler}
import org.apache.pekko.pattern.StatusReply
import org.apache.pekko.persistence._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.{ShiftStateSnapshotMessage, ShiftsMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike}

import scala.collection.immutable


case object GetFeedStatuses

trait ShiftUpdate

trait ShiftsActorLike {

  def persistenceId: String

  val snapshotMessageToState: Any => ShiftAssignments = {
    case snapshot: ShiftStateSnapshotMessage =>
      shiftMessagesToShiftAssignments(snapshot.shifts)
  }

  val eventToState: (() => SDateLike) => (ShiftAssignments, Any) => (ShiftAssignments, Iterable[TerminalUpdateRequest]) =
    now => (state, msg) => msg match {
      case m: ShiftsMessage =>
        val shiftsToRecover = shiftMessagesToShiftAssignments(m.shifts)
        val updatedShifts = state.applyUpdates(shiftsToRecover.assignments)
        val newState = updatedShifts.purgeExpired(startOfTheMonth(now))
        val subscriberEvents = terminalUpdateRequests(shiftsToRecover)
        (newState, subscriberEvents)
      case _ => (state, Seq.empty)
    }

  val query: (() => SDateLike) => (() => ShiftAssignments, () => ActorRef) => PartialFunction[Any, Unit] =
    now => (getState, getSender) => {
      case GetState =>
        getSender() ! getState().purgeExpired(startOfTheMonth(now))

      case GetStateForDateRange(from, to) =>
        getSender() ! ShiftAssignments(getState().assignments.filter(a => from <= a.start && a.end <= to))

      case TerminalUpdateRequest(terminal, localDate) =>
        val assignmentsForDate = ShiftAssignments(getState().assignments.filter { assignment =>
          val sdate = SDate(localDate)
          assignment.terminal == terminal &&
            (sdate.millisSinceEpoch <= assignment.end || assignment.start <= sdate.getLocalNextMidnight.millisSinceEpoch)
        })
        getSender() ! assignmentsForDate
    }

  def streamingUpdatesProps(persistenceId: String, journalType: StreamingJournalLike, now: () => SDateLike): Props =
    Props(new StreamingUpdatesActor[ShiftAssignments, Iterable[TerminalUpdateRequest]](
      persistenceId,
      journalType,
      ShiftAssignments.empty,
      snapshotMessageToState,
      eventToState(now),
      query(now)
    ))

  def terminalUpdateRequests(shifts: ShiftAssignments): immutable.Iterable[TerminalUpdateRequest] =
    shifts.assignments.groupBy(_.terminal).collect {
      case (terminal, assignments) if shifts.assignments.nonEmpty =>
        val earliest = SDate(assignments.map(_.start).min).millisSinceEpoch
        val latest = SDate(assignments.map(_.end).max).millisSinceEpoch
        (earliest to latest by MilliTimes.oneDayMillis).map { milli =>
          TerminalUpdateRequest(terminal, SDate(milli).toLocalDate)
        }
    }.flatten
}

class ShiftAssignmentsActorLike(val persistenceId: String,
                                val now: () => SDateLike,
                                val expireBefore: () => SDateLike,
                                val snapshotInterval: Int,
                               ) extends ExpiryActorLike[ShiftAssignments] with RecoveryActorLike with PersistentDrtActor[ShiftAssignments] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val scheduler: Scheduler = this.context.system.scheduler

  override val maybeSnapshotInterval: Option[Int] = Option(snapshotInterval)

  var state: ShiftAssignments = initialState

  def initialState: ShiftAssignments = ShiftAssignments.empty

  import ShiftAssignmentsMessageParser._

  override def stateToMessage: GeneratedMessage = {
    terminalDays.foreach {
      case (terminal, shiftCount, days) =>
        log.info(s"ShiftsActor stateToMessage: $shiftCount shifts for $terminal, ${days.mkString(", ")}")
    }

    ShiftStateSnapshotMessage(shiftAssignmentsToShiftsMessages(state, now()))
  }

  def updateState(shifts: ShiftAssignments): Unit = state = shifts

  def onUpdateState(data: ShiftAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage =>
      log.info(s"Processing a snapshot message")
      state = shiftMessagesToShiftAssignments(snapshot.shifts)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case sm: ShiftsMessage =>
      log.info(s"Recovery: ShiftsMessage received with ${sm.shifts.length} shifts")
      val shiftsToRecover = shiftMessagesToShiftAssignments(sm.shifts)
      val updatedShifts = state.applyUpdates(shiftsToRecover.assignments)
      purgeExpiredAndUpdateState(updatedShifts)
  }

  override def postRecoveryComplete(): Unit = terminalDays.foreach {
    case (terminal, shiftCount, days) =>
      log.info(s"ShiftsActor recovered: $shiftCount shifts for $terminal, ${days.mkString(", ")}")
  }

  def terminalDays: immutable.Iterable[(Terminal, Int, Seq[String])] = state.assignments
    .groupBy(_.terminal)
    .map { case (terminal, assignments) =>
      val days = assignments
        .groupBy(a => SDate(a.start).toISODateOnly)
        .keys
      (terminal, assignments.size, days.toSeq.sorted)
    }

  def receiveCommand: Receive = {
    case GetState =>
      log.debug(s"GetState received")
      val assignments = state.purgeExpired(expireBefore)
      sender() ! assignments

    case TerminalUpdateRequest(terminal, localDate) =>
      sender() ! ShiftAssignments(state.assignments.filter { assignment =>
        val sdate = SDate(localDate)
        assignment.terminal == terminal &&
          (sdate.millisSinceEpoch <= assignment.end || assignment.start <= sdate.getLocalNextMidnight.millisSinceEpoch)
      })

    case UpdateShifts(shiftsToUpdate) =>
      val updatedShifts = state.applyUpdates(shiftsToUpdate)
      purgeExpiredAndUpdateState(updatedShifts)
      val createdAt = now()
      val shiftsMessage = ShiftsMessage(shiftAssignmentsToShiftsMessages(ShiftAssignments(shiftsToUpdate), createdAt), Option(createdAt.millisSinceEpoch))

      persistAndMaybeSnapshotWithAck(shiftsMessage, List(
        (sender(), state),
      ))

    case ReplaceAllShifts(newShiftAssignments) =>
      if (newShiftAssignments != state) {
        log.info(s"Replacing shifts state with ${newShiftAssignments.size} shifts")
        purgeExpiredAndUpdateState(ShiftAssignments(newShiftAssignments))

        val createdAt = now()
        val shiftsMessage = ShiftsMessage(shiftAssignmentsToShiftsMessages(ShiftAssignments(newShiftAssignments), createdAt), Option(createdAt.millisSinceEpoch))

        persistAndMaybeSnapshotWithAck(shiftsMessage, List((sender(), StatusReply.Ack)))
      } else {
        log.info(s"No change. Nothing to persist")
        sender() ! Iterable()
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")
      ackIfRequired()

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"unhandled message: $unexpected")
  }
}
