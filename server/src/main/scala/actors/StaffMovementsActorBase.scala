package actors

import java.util.UUID

import akka.actor.DiagnosticActorLogging
import akka.persistence._
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.{MilliDate, StaffMovement}
import server.protobuf.messages.StaffMovementMessages.{StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.SDate

case class StaffMovements(staffMovements: Seq[StaffMovement])

case class StaffMovementsState(staffMovements: StaffMovements) {
  def updated(data: StaffMovements): StaffMovementsState = copy(staffMovements = data)
}

case class AddStaffMovementsSubscribers(subscribers: List[SourceQueueWithComplete[Seq[StaffMovement]]])

class StaffMovementsActor extends StaffMovementsActorBase {
  var subscribers: List[SourceQueueWithComplete[Seq[StaffMovement]]] = List()

  override def onUpdateState(data: StaffMovements): Unit = {
    log.info(s"Telling subscribers about updated staff movements")
    subscribers.map(_.offer(data.staffMovements))
  }

  val subsReceive: Receive = {
    case AddStaffMovementsSubscribers(newSubscribers) =>
      subscribers = newSubscribers.foldLeft(subscribers) {
        case (soFar, newSub: SourceQueueWithComplete[Seq[StaffMovement]]) =>
          log.info(s"Adding staff movements subscriber $newSub")
          newSub :: soFar
      }
  }

  override def receiveCommand: Receive = {
    subsReceive orElse super.receiveCommand
  }
}

class StaffMovementsActorBase extends PersistentActor
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
      val sm = staffMovementMessagesToStaffMovements(smm.staffMovements.toList)
      updateState(sm)

    case SnapshotOffer(_, snapshot: StaffMovementsStateSnapshotMessage) =>
      state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList))

    case RecoveryCompleted =>
      log.info("RecoveryCompleted")
  }

  def receiveCommand: Receive = {
    case GetState =>
      log.info(s"GetState received")
      sender() ! state.staffMovements

    case sm@StaffMovements(_) =>
      if (sm != state.staffMovements) {
        updateState(sm)
        onUpdateState(sm)

        log.info(s"Staff movements updated. Saving snapshot")
        saveSnapshot(StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(state.staffMovements)))
      } else {
        log.info(s"No changes to staff movements. Not persisting")
      }

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case u =>
      log.info(s"unhandled message: $u")
  }

  def onUpdateState(sm: StaffMovements) = {}

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
