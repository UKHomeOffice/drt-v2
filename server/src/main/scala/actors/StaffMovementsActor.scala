package actors

import java.util.UUID

import akka.actor.DiagnosticActorLogging
import akka.persistence._
import drt.shared.{MilliDate, StaffMovement}
import server.protobuf.messages.StaffMovementMessages.{StaffMovementMessage, StaffMovementsMessage, StaffMovementsStateSnapshotMessage}
import services.SDate

import scala.collection.immutable.Seq

case class StaffMovements(staffMovements: Seq[StaffMovement])

case class StaffMovementsState(events: List[StaffMovements] = Nil) {
  def updated(data: StaffMovements): StaffMovementsState = copy(data :: events)

  def size: Int = events.length

  override def toString: String = events.reverse.toString
}

class StaffMovementsActor extends PersistentActor
  with DiagnosticActorLogging {

  override def persistenceId = "staff-movements-store"

  var state = StaffMovementsState()

  val snapshotInterval = 5

  def updateState(data: StaffMovements): Unit = {
    state = state.updated(data)
  }

  def staffMovementMessagesToStaffMovements(messages: List[StaffMovementMessage]): StaffMovements = StaffMovements(messages.map(staffMovementMessageToStaffMovement))

  val receiveRecover: Receive = {
    case smm: StaffMovementsMessage =>
      updateState(staffMovementMessagesToStaffMovements(smm.staffMovements.toList))
    case SnapshotOffer(_, snapshot: StaffMovementsStateSnapshotMessage) =>
      state = StaffMovementsState(staffMovementMessagesToStaffMovements(snapshot.staffMovements.toList) :: Nil)
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

  val receiveCommand: Receive = {
    case GetState =>
      sender() ! state.events.headOption.getOrElse("")
    case staffMovements: StaffMovements =>
      val staffMovementsMessage = staffMovementsToStaffMovementsMessage(staffMovements)
      persist(staffMovementsMessage) { (staffMovementsMessage: StaffMovementsMessage) =>
        context.system.eventStream.publish(staffMovementsMessage)
      }
      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info(s"saving shifts snapshot info snapshot (lastSequenceNr: $lastSequenceNr)")
        state.events.headOption.foreach {
          case staffMovements: StaffMovements =>
            saveSnapshot(StaffMovementsStateSnapshotMessage(staffMovementsToStaffMovementMessages(staffMovements)))
        }
      }
      updateState(staffMovements)

  }

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
