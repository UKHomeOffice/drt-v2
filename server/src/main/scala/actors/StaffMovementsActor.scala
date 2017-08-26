package actors

import java.util.UUID

import akka.actor.DiagnosticActorLogging
import akka.persistence._
import drt.shared.{MilliDate, StaffMovement}
import server.protobuf.messages.StaffMovementMessages.{StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.SDate

import scala.collection.immutable.Seq

case class StaffMovements(staffMovements: Seq[StaffMovement])

case class StaffMovementsState(staffMovements: StaffMovements) {
  def updated(data: StaffMovements): StaffMovementsState = copy(staffMovements = data)
}

class StaffMovementsActor extends PersistentActor
  with DiagnosticActorLogging {

  override def persistenceId = "staff-movements-store"

  var state = StaffMovementsState(StaffMovements(List()))

  val snapshotInterval = 1

  def updateState(data: StaffMovements): Unit = {
    state = state.updated(data)
  }

  def staffMovementMessagesToStaffMovements(messages: List[StaffMovementMessage]): StaffMovements = StaffMovements(messages.map(staffMovementMessageToStaffMovement))

  val receiveRecover: Receive = {
    case smm: StaffMovementsMessage =>
      updateState(staffMovementMessagesToStaffMovements(smm.staffMovements.toList))

    case SnapshotOffer(_, snapshot: StaffMovementsStateSnapshotMessage) =>
      state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))
  }

  val receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.staffMovements

    case sm@StaffMovements(_) =>
      if (sm != state.staffMovements) {
        updateState(sm)
        log.info(s"Staff movements updated. Saving snapshot")
        saveSnapshot(StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(state.staffMovements)))
      } else {
        log.info(s"No changes to staff movements. Not persisting")
      }

    case u =>
      log.info(s"unhandled message: $u")
  }

  def staffMovementMessageToStaffMovement(sm: StaffMovementMessage) = StaffMovement(
    terminalName = sm.terminalName.getOrElse(""),
    reason = sm.reason.getOrElse(""),
    time = MilliDate(sm.time.getOrElse(0)),
    delta = sm.delta.getOrElse(0),
    uUID = UUID.fromString(sm.uUID.getOrElse("")),
    queue = sm.queueName
  )

  def staffMovementsToStaffMovementMessages(staffMovements: StaffMovements): Seq[StaffMovementMessage] =
    staffMovements.staffMovements.map(staffMovementToStaffMovementMessage)

  def staffMovementsToStaffMovementsMessage(staffMovements: StaffMovements) =
    StaffMovementsMessage(staffMovements.staffMovements.map(staffMovementToStaffMovementMessage))

  def staffMovementToStaffMovementMessage(sm: StaffMovement) = StaffMovementMessage(
    terminalName = Some(sm.terminalName),
    reason = Some(sm.reason),
    time = Some(sm.time.millisSinceEpoch),
    delta = Some(sm.delta),
    uUID = Some(sm.uUID.toString),
    queueName = sm.queue,
    createdAt = Option(SDate.now().millisSinceEpoch)
  )
}
