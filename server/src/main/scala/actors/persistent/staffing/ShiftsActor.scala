package actors.persistent.staffing

import actors.DrtStaticParameters.startOfTheMonth
import actors.PartitionedPortStateActor.GetStateForDateRange
import actors.daily.RequestAndTerminate
import actors.persistent.StreamingUpdatesActor
import actors.persistent.staffing.ShiftsActor.{ReplaceAllShifts, UpdateShifts, applyUpdatedShifts}
import actors.persistent.staffing.ShiftsMessageParser.{log, shiftMessagesToStaffAssignments}
import actors.routing.SequentialWritesActor
import actors.{ExpiryActorLike, StreamingJournalLike}
import akka.actor.{ActorRef, ActorSystem, Props, Scheduler}
import akka.pattern.{StatusReply, ask}
import akka.persistence._
import akka.util.Timeout
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, MilliTimes, SDate, SDateLike}

import scala.concurrent.duration._
import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.util.Try


case object GetFeedStatuses

trait ShiftsActorLike {
  def persistenceId = "shifts-store"

  val snapshotMessageToState: Any => ShiftAssignments = {
    case snapshot: ShiftStateSnapshotMessage =>
      shiftMessagesToStaffAssignments(snapshot.shifts)
  }

  val eventToState: (() => SDateLike) => (ShiftAssignments, Any) => (ShiftAssignments, Iterable[TerminalUpdateRequest]) =
    now => (state, msg) => msg match {
      case m: ShiftsMessage =>
        val shiftsToRecover = shiftMessagesToStaffAssignments(m.shifts)
        val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToRecover.assignments)
        val newState = ShiftAssignments(updatedShifts).purgeExpired(startOfTheMonth(now))
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

  def streamingUpdatesProps(journalType: StreamingJournalLike, now: () => SDateLike): Props =
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

object ShiftsActor extends ShiftsActorLike {
  val snapshotInterval = 5000

  trait ShiftUpdate

  case class ReplaceAllShifts(newShifts: Seq[StaffAssignmentLike]) extends ShiftUpdate

  case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignmentLike]) extends ShiftUpdate

  case class SetMinimumStaff(terminal: Terminal,
                             startDate: LocalDate,
                             endDate: LocalDate,
                             newMinimum: Option[Int],
                             previousMinimum: Option[Int]) extends ShiftUpdate

  def applyUpdatedShifts(existingAssignments: Seq[StaffAssignmentLike],
                         shiftsToUpdate: Seq[StaffAssignmentLike]): Seq[StaffAssignmentLike] = {
    val createdAt = SDate.now()
    val updatedShifts = SplitUtil.applyUpdatedShifts(existingAssignments, shiftsToUpdate)
    log.info(s"Shifts updated took ${SDate.now.millisSinceEpoch-createdAt.millisSinceEpoch} ms")
    updatedShifts
  }


  def sequentialWritesProps(now: () => SDateLike,
                            expireBefore: () => SDateLike,
                            requestAndTerminateActor: ActorRef,
                            system: ActorSystem
                           )
                           (implicit timeout: Timeout, ec: ExecutionContext): Props =
    Props(new SequentialWritesActor[ShiftUpdate](update => {
      val actor = system.actorOf(Props(new ShiftsActor(now, expireBefore, snapshotInterval)), s"shifts-actor-writes")
      requestAndTerminateActor.ask(RequestAndTerminate(actor, update))
    }))

}

class ShiftsActor(val now: () => SDateLike,
                  val expireBefore: () => SDateLike,
                  val snapshotInterval: Int,
                 ) extends ExpiryActorLike[ShiftAssignments] with RecoveryActorLike with PersistentDrtActor[ShiftAssignments] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  implicit val scheduler: Scheduler = this.context.system.scheduler

  override val maybeSnapshotInterval: Option[Int] = Option(snapshotInterval)

  override def persistenceId: String = ShiftsActor.persistenceId

  var state: ShiftAssignments = initialState

  def initialState: ShiftAssignments = ShiftAssignments.empty

  import ShiftsMessageParser._

  override def stateToMessage: GeneratedMessage = {
    terminalDays.foreach {
      case (terminal, shiftCount, days) =>
        log.info(s"ShiftsActor stateToMessage: $shiftCount shifts for $terminal, ${days.mkString(", ")}")
    }

    ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state, now()))
  }

  def updateState(shifts: ShiftAssignments): Unit = state = shifts

  def onUpdateState(data: ShiftAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage =>
      log.info(s"Processing a snapshot message")
      state = shiftMessagesToStaffAssignments(snapshot.shifts)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case sm: ShiftsMessage =>
      log.info(s"Recovery: ShiftsMessage received with ${sm.shifts.length} shifts")
      val shiftsToRecover = shiftMessagesToStaffAssignments(sm.shifts)
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToRecover.assignments)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))
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
      //      println(s"state.assignments: ${state.assignments}")
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToUpdate)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))
      //    println(s"updatedShifts: $updatedShifts")
      val createdAt = now()
      val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(shiftsToUpdate), createdAt), Option(createdAt.millisSinceEpoch))

      persistAndMaybeSnapshotWithAck(shiftsMessage, List(
        (sender(), state),
      ))

    case ReplaceAllShifts(newShiftAssignments) =>
      if (newShiftAssignments != state) {
        log.info(s"Replacing shifts state with ${newShiftAssignments.size} shifts")
        purgeExpiredAndUpdateState(ShiftAssignments(newShiftAssignments))

        val createdAt = now()
        val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(newShiftAssignments), createdAt), Option(createdAt.millisSinceEpoch))

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

object ShiftsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignmentLike, createdAt: SDateLike): ShiftMessage = ShiftMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminal.toString),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.start),
    endTimestamp = Option(assignment.end),
    createdAt = Option(createdAt.millisSinceEpoch)
  )

  private def shiftMessageToStaffAssignmentv1(shiftMessage: ShiftMessage): Option[StaffAssignment] = {
    val maybeSt: Option[SDateLike] = parseDayAndTimeToSdate(shiftMessage.startDayOLD, shiftMessage.startTimeOLD)
    val maybeEt: Option[SDateLike] = parseDayAndTimeToSdate(shiftMessage.startDayOLD, shiftMessage.endTimeOLD)
    for {
      startDt <- maybeSt
      endDt <- maybeEt
    } yield {
      StaffAssignment(
        name = shiftMessage.name.getOrElse(""),
        terminal = Terminal(shiftMessage.terminalName.getOrElse("")),
        start = startDt.roundToMinute().millisSinceEpoch,
        end = endDt.roundToMinute().millisSinceEpoch,
        numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
        createdBy = None
      )
    }
  }

  private def parseDayAndTimeToSdate(maybeDay: Option[String], maybeTime: Option[String]): Option[SDateLike] = {
    val maybeDayMonthYear = maybeDay.getOrElse("1/1/0").split("/") match {
      case Array(d, m, y) => Try((d.toInt, m.toInt, y.toInt + 2000)).toOption
      case _ => None
    }
    val maybeHourMinute = maybeTime.getOrElse("00:00").split(":") match {
      case Array(a, b) => Try((a.toInt, b.toInt)).toOption
      case _ => None
    }

    for {
      (d, m, y) <- maybeDayMonthYear
      (hr, min) <- maybeHourMinute
    } yield SDate(y, m, d, hr, min, europeLondonTimeZone)
  }

  private def shiftMessageToStaffAssignmentv2(shiftMessage: ShiftMessage): Option[StaffAssignment] = Option(StaffAssignment(
    name = shiftMessage.name.getOrElse(""),
    terminal = Terminal(shiftMessage.terminalName.getOrElse("")),
    start = shiftMessage.startTimestamp.getOrElse(0L),
    end = shiftMessage.endTimestamp.getOrElse(0L),
    numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
    createdBy = None
  ))

  def staffAssignmentsToShiftsMessages(shiftStaffAssignments: ShiftAssignments,
                                       createdAt: SDateLike): Seq[ShiftMessage] =
    shiftStaffAssignments.assignments.map(a => staffAssignmentToMessage(a, createdAt))

  def shiftMessagesToStaffAssignments(shiftMessages: Seq[ShiftMessage]): ShiftAssignments =
    ShiftAssignments(shiftMessages.collect {
      case sm@ShiftMessage(Some(_), Some(_), Some(_), Some(_), Some(_), Some(_), None, None, _) => shiftMessageToStaffAssignmentv1(sm)
      case sm@ShiftMessage(Some(_), Some(_), None, None, None, Some(_), Some(_), Some(_), _) => shiftMessageToStaffAssignmentv2(sm)
    }.flatten)
}
