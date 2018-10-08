package actors

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}


case object GetState

case object GetFeedStatuses

case class GetPortState(from: MillisSinceEpoch, to: MillisSinceEpoch)

case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch)

case object GetShifts

case class SetShifts(newShifts: Seq[StaffAssignment])

case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignment])

case class AddShiftSubscribers(subscribers: List[SourceQueueWithComplete[ShiftAssignments]])

case class AddFixedPointSubscribers(subscribers: List[SourceQueueWithComplete[FixedPointAssignments]])

class ShiftsActor(now: () => SDateLike, expireBefore: () => SDateLike) extends ShiftsActorBase(now, expireBefore) {
  var subscribers: List[SourceQueueWithComplete[ShiftAssignments]] = List()

  override def onUpdateState(shifts: ShiftAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated shifts")

    subscribers.foreach(s => {
      s.offer(shifts).onComplete {
        case Success(qor) => log.info(s"update queued successfully with subscriber: $qor")
        case Failure(t) => log.info(s"update failed to queue with subscriber: $t")
      }
    })
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

  override def persistenceId = "shifts-store"

  var state: ShiftAssignments = initialState

  def initialState: ShiftAssignments = ShiftAssignments.empty

  val snapshotInterval = 250
  override val snapshotBytesThreshold: Int = oneMegaByte

  import ShiftsMessageParser._

  override def stateToMessage: GeneratedMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state, now()))

  def updateState(shifts: ShiftAssignments): Unit = state = shifts

  def onUpdateState(data: ShiftAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = shiftMessagesToStaffAssignments(snapshot.shifts)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case ShiftsMessage(shiftMessages, _) =>
      log.info(s"Recovery: ShiftsMessage received with ${shiftMessages.length} shifts")
      val shiftsToRecover = shiftMessagesToStaffAssignments(shiftMessages)
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToRecover.assignments)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.purgeExpired(expireBefore)

    case UpdateShifts(shiftsToUpdate) =>
      val updatedShifts = applyUpdatedShifts(state.assignments, shiftsToUpdate)
      purgeExpiredAndUpdateState(ShiftAssignments(updatedShifts))

      val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(shiftsToUpdate), now()))
      persistAndMaybeSnapshot(shiftsMessage)

    case SetShifts(newShiftAssignments) =>
      if (newShiftAssignments != state) {
        log.info(s"Replacing shifts state with $newShiftAssignments")
        updateState(ShiftAssignments(newShiftAssignments))
        purgeExpiredAndUpdateState(ShiftAssignments(newShiftAssignments))

        val shiftsMessage = ShiftsMessage(staffAssignmentsToShiftsMessages(ShiftAssignments(newShiftAssignments), now()))
        persistAndMaybeSnapshot(shiftsMessage)
      } else log.info(s"No change. Nothing to persist")

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
