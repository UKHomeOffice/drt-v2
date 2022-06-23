package actors.persistent.staffing

import actors.ExpiryActorLike
import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.Sizes.oneMegaByte
import actors.persistent.{PersistentDrtActor, RecoveryActorLike}
import akka.actor.{ActorRef, Scheduler}
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.SDate
import services.crunch.deskrecs.RunnableOptimisation.TerminalUpdateRequest
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import scala.util.Try


case object GetState

case object ClearState

case object GetFeedStatuses

case object GetShifts

case class SetShifts(newShifts: Seq[StaffAssignmentLike])

case class SetShiftsAck(newShifts: Seq[StaffAssignmentLike])

case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignmentLike])

case class UpdateShiftsAck(shiftsToUpdate: Seq[StaffAssignmentLike])

case object SaveSnapshot


class ShiftsActor(now: () => SDateLike, expireBefore: () => SDateLike) extends ShiftsActorBase(now, expireBefore) {

  override def onUpdateDiff(shifts: ShiftAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated shifts")
    shifts.assignments.groupBy(_.terminal).foreach { case (terminal, assignments) =>
      if (shifts.assignments.nonEmpty) {
        val earliest = SDate(assignments.map(_.start).min).millisSinceEpoch
        val latest = SDate(assignments.map(_.end).max).millisSinceEpoch
        val updateRequests = (earliest to latest by MilliTimes.oneDayMillis).map { milli =>
          TerminalUpdateRequest(terminal, SDate(milli).toLocalDate, 0, 1440)
        }
        subscribers.foreach(sub => updateRequests.foreach(sub ! _))
      }
    }
  }

  val subsReceive: Receive = {
    case actor: ActorRef =>
      log.info(s"received a subscriber")
      subscribers = actor :: subscribers
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class ShiftsActorBase(val now: () => SDateLike,
                      val expireBefore: () => SDateLike) extends ExpiryActorLike[ShiftAssignments] with RecoveryActorLike with PersistentDrtActor[ShiftAssignments] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  var subscribers: List[ActorRef] = List()
  implicit val scheduler: Scheduler = this.context.system.scheduler

  val snapshotInterval = 5000
  override val snapshotBytesThreshold: Int = oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(snapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def persistenceId = "shifts-store"

  var state: ShiftAssignments = initialState

  def initialState: ShiftAssignments = ShiftAssignments.empty

  import ShiftsMessageParser._

  override def stateToMessage: GeneratedMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state, now()))

  def updateState(shifts: ShiftAssignments): Unit = state = shifts

  def onUpdateState(data: ShiftAssignments): Unit = {}

  def onUpdateDiff(diff: ShiftAssignments): Unit = {}

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

  def receiveCommand: Receive = {
    case GetState =>
      log.debug(s"GetState received")
      val assignments = state.purgeExpired(expireBefore)
      sender() ! assignments

    case TerminalUpdateRequest(terminal, localDate, _, _) =>
      sender() ! ShiftAssignments(state.assignments.filter { assignment =>
        val sdate = SDate(localDate)
        assignment.terminal == terminal &&
          (sdate.millisSinceEpoch <= assignment.end || assignment.start <= sdate.getLocalNextMidnight.millisSinceEpoch)
      })

    case UpdateShifts(shiftsToUpdate) =>
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToUpdate)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))

      val createdAt = now()
      val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(shiftsToUpdate), createdAt), Option(createdAt.millisSinceEpoch))

      persistAndMaybeSnapshotWithAck(shiftsMessage, List(
        (sender(), UpdateShiftsAck(shiftsToUpdate)),
      ))

      onUpdateDiff(ShiftAssignments(shiftsToUpdate))

    case SetShifts(newShiftAssignments) =>
      if (newShiftAssignments != state) {
        log.info(s"Replacing shifts state with $newShiftAssignments")
        purgeExpiredAndUpdateState(ShiftAssignments(newShiftAssignments))

        val createdAt = now()
        val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(newShiftAssignments), createdAt), Option(createdAt.millisSinceEpoch))
        persistAndMaybeSnapshotWithAck(shiftsMessage, List((sender(), SetShiftsAck(newShiftAssignments))))
        onUpdateDiff(ShiftAssignments(newShiftAssignments))
      } else {
        log.info(s"No change. Nothing to persist")
        sender() ! SetShiftsAck(newShiftAssignments)
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")
      ackIfRequired()

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case SaveSnapshot =>
      log.info(s"Received request to snapshot")
      takeSnapshot(stateToMessage)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.info(s"unhandled message: $unexpected")
  }

  def applyUpdatedShifts(existingAssignments: Seq[StaffAssignmentLike],
                         shiftsToUpdate: Seq[StaffAssignmentLike]): Seq[StaffAssignmentLike] = shiftsToUpdate
    .foldLeft(existingAssignments) {
      case (assignmentsSoFar, updatedAssignment) =>
        assignmentsSoFar.filterNot { existing =>
          existing.start == updatedAssignment.start && existing.terminal == updatedAssignment.terminal
        }
    } ++ shiftsToUpdate
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

  def shiftMessageToStaffAssignmentv1(shiftMessage: ShiftMessage): Option[StaffAssignment] = {
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

  def parseDayAndTimeToSdate(maybeDay: Option[String], maybeTime: Option[String]): Option[SDateLike] = {
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
    } yield SDate(y, m, d, hr, min, Crunch.europeLondonTimeZone)
  }

  def shiftMessageToStaffAssignmentv2(shiftMessage: ShiftMessage): Option[StaffAssignment] = Option(StaffAssignment(
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
