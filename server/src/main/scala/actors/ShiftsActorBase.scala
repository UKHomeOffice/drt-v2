package actors

import actors.Sizes.oneMegaByte
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{MilliDate, StaffAssignment, StaffAssignments}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.ShiftMessage.{ShiftMessage, ShiftStateSnapshotMessage, ShiftsMessage}
import services.SDate
import services.graphstages.StaffAssignmentHelper

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

case class ShiftsState(shifts: StaffAssignments) {
  def updated(data: StaffAssignments): ShiftsState = copy(shifts = data)
}

case object GetState

case object GetFeedStatuses

case class GetPortState(from: MillisSinceEpoch, to: MillisSinceEpoch)

case class GetUpdatesSince(millis: MillisSinceEpoch, from: MillisSinceEpoch, to: MillisSinceEpoch)

case object GetShifts

case class AddShiftLikeSubscribers(subscribers: List[SourceQueueWithComplete[String]])

class ShiftsActor extends ShiftsActorBase {
  var subscribers: List[SourceQueueWithComplete[String]] = List()

  override def onUpdateState(data: StaffAssignments): Unit = {
    log.info(s"Telling subscribers ($subscribers) about updated shifts")

    subscribers.foreach(s => {
      s.offer(StaffAssignmentHelper.staffAssignmentsToString(data.assignments)).onComplete {
        case Success(qor) => log.info(s"update queued successfully with subscriber: $qor")
        case Failure(t) => log.info(s"update failed to queue with subscriber: $t")
      }
    })
  }

  val subsReceive: Receive = {
    case AddShiftLikeSubscribers(newSubscribers) =>
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub: SourceQueueWithComplete[String]) =>
          log.info(s"Adding shifts subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class ShiftsActorBase extends RecoveryActorLike with PersistentDrtActor[ShiftsState] {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId = "shifts-store"

  var state = initialState

  def initialState = ShiftsState (StaffAssignmentHelper.empty)

  val snapshotInterval = 1
  override val snapshotBytesThreshold: Int = oneMegaByte

  import ShiftsMessageParser._

  override def stateToMessage: GeneratedMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state.shifts))

  def updateState(shifts: StaffAssignments): Unit = state = state.updated(data = shifts)

  def onUpdateState(data: StaffAssignments): Unit = {}

  def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case snapshot: ShiftStateSnapshotMessage => state = ShiftsState(shiftMessagesToStaffAssignments(snapshot.shifts))
  }

  def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case shiftsMessage: ShiftsMessage =>
      log.info(s"Recovery: ShiftsMessage received with ${shiftsMessage.shifts.length} shifts")
      val shifts = shiftMessagesToStaffAssignments(shiftsMessage.shifts)
      updateState(shifts)
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.shifts

    case shiftStaffAssignments: StaffAssignments if shiftStaffAssignments != state.shifts =>
      updateState(shiftStaffAssignments)
      onUpdateState(shiftStaffAssignments)

      log.info(s"Fixed points updated. Saving snapshot")
      val snapshotMessage = ShiftStateSnapshotMessage(staffAssignmentsToShiftsMessages(state.shifts))
      saveSnapshot(snapshotMessage)

    case shiftStaffAssignments: StaffAssignments if shiftStaffAssignments == state.shifts =>
      log.info(s"No changes to fixed points. Not persisting")

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }
}

object ShiftsMessageParser {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def staffAssignmentToMessage(assignment: StaffAssignment): ShiftMessage = ShiftMessage(
    name = Option(assignment.name),
    terminalName = Option(assignment.terminalName),
    numberOfStaff = Option(assignment.numberOfStaff.toString),
    startTimestamp = Option(assignment.startDt.millisSinceEpoch),
    endTimestamp = Option(assignment.endDt.millisSinceEpoch),
    createdAt = Option(SDate.now().millisSinceEpoch)
  )

  def shiftMessageToStaffAssignment(shiftMessage: ShiftMessage) = StaffAssignment(
    name = shiftMessage.name.getOrElse(""),
    terminalName = shiftMessage.terminalName.getOrElse(""),
    startDt = MilliDate(shiftMessage.startTimestamp.getOrElse(0L)),
    endDt = MilliDate(shiftMessage.endTimestamp.getOrElse(0L)),
    numberOfStaff = shiftMessage.numberOfStaff.getOrElse("0").toInt,
    createdBy = None
  )

  def staffAssignmentsToShiftsMessages(shiftStaffAssignments: StaffAssignments): Seq[ShiftMessage] =
    shiftStaffAssignments.assignments.map(staffAssignmentToMessage)

  def shiftMessagesToStaffAssignments(shiftMessages: Seq[ShiftMessage]): StaffAssignments =
    StaffAssignments(shiftMessages.map(shiftMessageToStaffAssignment))
}
