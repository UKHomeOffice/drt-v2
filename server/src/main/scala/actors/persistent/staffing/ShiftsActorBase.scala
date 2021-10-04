package actors.persistent.staffing

import actors.persistent.Sizes.oneMegaByte
import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.{PersistentDrtActor, RecoveryActorLike}
import actors.ExpiryActorLike
import akka.actor.Scheduler
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import services.graphstages.Crunch
import services.{OfferHandler, SDate}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Try


case object GetState

case object ClearState

case object GetFeedStatuses

case object GetShifts

case class SetShifts(newShifts: Seq[StaffAssignment])

case class SetShiftsAck(newShifts: Seq[StaffAssignment])

case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignment])

case class UpdateShiftsAck(shiftsToUpdate: Seq[StaffAssignment])

case class AddShiftSubscribers(subscribers: List[SourceQueueWithComplete[ShiftAssignments]])

case class AddFixedPointSubscribers(subscribers: List[SourceQueueWithComplete[FixedPointAssignments]])

case object SaveSnapshot


class ShiftsActor(now: () => SDateLike, expireBefore: () => SDateLike) extends ShiftsActorBase(now, expireBefore) {
  var subscribers: List[SourceQueueWithComplete[ShiftAssignments]] = List()
  implicit val scheduler: Scheduler = this.context.system.scheduler

  override def onUpdateState(shifts: ShiftAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated shifts")

    subscribers.foreach(s => OfferHandler.offerWithRetries(s, shifts, 5))
  }

  val subsReceive: Receive = {
    case AddShiftSubscribers(newSubscribers) =>
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub) =>
          log.info(s"Adding shifts subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class ShiftsActorBase(val now: () => SDateLike,
                      val expireBefore: () => SDateLike) extends ExpiryActorLike[ShiftAssignments] with RecoveryActorLike with PersistentDrtActor[ShiftAssignments] {
  val log: Logger = LoggerFactory.getLogger(getClass)

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

    case UpdateShifts(shiftsToUpdate) =>
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToUpdate)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))

      val createdAt = now()
      val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(shiftsToUpdate), createdAt), Option(createdAt.millisSinceEpoch))
      persistAndMaybeSnapshotWithAck(shiftsMessage, Option((sender(), UpdateShiftsAck(shiftsToUpdate))))

    case SetShifts(newShiftAssignments) =>
      if (newShiftAssignments != state) {
        log.info(s"Replacing shifts state with $newShiftAssignments")
        purgeExpiredAndUpdateState(ShiftAssignments(newShiftAssignments))

        val createdAt = now()
        val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(newShiftAssignments), createdAt), Option(createdAt.millisSinceEpoch))
        persistAndMaybeSnapshotWithAck(shiftsMessage, Option((sender(), SetShiftsAck(newShiftAssignments))))
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

  def applyUpdatedShifts(existingAssignments: Seq[StaffAssignment],
                         shiftsToUpdate: Seq[StaffAssignment]): Seq[StaffAssignment] = shiftsToUpdate
    .foldLeft(existingAssignments) {
      case (assignmentsSoFar, updatedAssignment) =>
        assignmentsSoFar.filterNot { existing =>
          existing.startDt == updatedAssignment.startDt && existing.terminal == updatedAssignment.terminal
        }
    } ++ shiftsToUpdate
}

object ShiftsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignment, createdAt: SDateLike): ShiftMessage = ShiftMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminal.toString),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.startDt.millisSinceEpoch),
    endTimestamp = Option(assignment.endDt.millisSinceEpoch),
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
        startDt = MilliDate(startDt.roundToMinute().millisSinceEpoch),
        endDt = MilliDate(endDt.roundToMinute().millisSinceEpoch),
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
    startDt = MilliDate(shiftMessage.startTimestamp.getOrElse(0L)),
    endDt = MilliDate(shiftMessage.endTimestamp.getOrElse(0L)),
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
