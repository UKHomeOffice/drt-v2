package actors

import actors.Sizes.oneMegaByte
import akka.actor.Scheduler
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import services.{OfferHandler, SDate}
import services.graphstages.Crunch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}


case object GetState

case object GetFeedStatuses

case class GetPortState(from: MillisSinceEpoch, to: MillisSinceEpoch)

case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch)

case object GetShifts

case class SetShifts(newShifts: Seq[StaffAssignment])

case class SetShiftsAck(newShifts: Seq[StaffAssignment])

case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignment])

case class UpdateShiftsAck(shiftsToUpdate: Seq[StaffAssignment])

case class AddShiftSubscribers(subscribers: List[SourceQueueWithComplete[ShiftAssignments]])

case class AddFixedPointSubscribers(subscribers: List[SourceQueueWithComplete[FixedPointAssignments]])

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

  override def persistenceId = "shifts-store"

  var state: ShiftAssignments = initialState

  def initialState: ShiftAssignments = ShiftAssignments.empty

  import ShiftsMessageParser._

  override def stateToMessage: GeneratedMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state, now()))

  def updateState(shifts: ShiftAssignments): Unit = state = shifts

  def onUpdateState(data: ShiftAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = shiftMessagesToStaffAssignments(snapshot.shifts)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case sm: ShiftsMessage =>
      log.info(s"Recovery: ShiftsMessage received with ${sm.shifts.length} shifts")
      val shiftsToRecover = shiftMessagesToStaffAssignments(sm.shifts)
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToRecover.assignments)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))

      bytesSinceSnapshotCounter += sm.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.purgeExpired(expireBefore)

    case UpdateShifts(shiftsToUpdate) =>
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToUpdate)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))

      val createdAt = now()
      val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(shiftsToUpdate), createdAt), Option(createdAt.millisSinceEpoch))
      persistAndMaybeSnapshot(shiftsMessage)

      sender() ! UpdateShiftsAck(shiftsToUpdate)

    case SetShifts(newShiftAssignments) =>
      if (newShiftAssignments != state) {
        log.info(s"Replacing shifts state with $newShiftAssignments")
        purgeExpiredAndUpdateState(ShiftAssignments(newShiftAssignments))

        val createdAt = now()
        val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(newShiftAssignments), createdAt), Option(createdAt.millisSinceEpoch))
        persistAndMaybeSnapshot(shiftsMessage)
      } else log.info(s"No change. Nothing to persist")

      sender() ! SetShiftsAck(newShiftAssignments)

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }

  def applyUpdatedShifts(existingAssignments: Seq[StaffAssignment], shiftsToUpdate: Seq[StaffAssignment]): Seq[StaffAssignment] = shiftsToUpdate
    .foldLeft(existingAssignments) {
      case (assignmentsSoFar, updatedAssignment) =>
        assignmentsSoFar.filterNot(existing =>
          existing.startDt == updatedAssignment.startDt && existing.terminalName == updatedAssignment.terminalName)
    } ++ shiftsToUpdate
}

object ShiftsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignment, createdAt: SDateLike): ShiftMessage = ShiftMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminalName),
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
    } yield StaffAssignment(
      name = shiftMessage.name.getOrElse(""),
      terminalName = shiftMessage.terminalName.getOrElse(""),
      startDt = MilliDate(startDt.millisSinceEpoch),
      endDt = MilliDate(endDt.millisSinceEpoch),
      numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
      createdBy = None
    )
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
    terminalName = shiftMessage.terminalName.getOrElse(""),
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
