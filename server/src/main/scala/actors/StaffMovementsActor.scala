package actors

import java.util.UUID

import akka.persistence._
import server.protobuf.messages.StaffMovementMessages.{StaffMovementMessage, StaffMovementsMessage}
import spatutorial.shared.{MilliDate, StaffMovement}

import scala.collection.immutable.Seq

case class StaffMovements(staffMovements: Seq[StaffMovement])

case class StaffMovementsState(events: List[StaffMovements] = Nil) {
  def updated(data: StaffMovements): StaffMovementsState = copy(data :: events)
  def size: Int = events.length
  override def toString: String = events.reverse.toString
}

class StaffMovementsActor extends PersistentActor {

    override def persistenceId = "staff-movements-store"

    var state = StaffMovementsState()

    def updateState(data: StaffMovements): Unit = {
      state = state.updated(data)
    }

    val receiveRecover: Receive = {
      case smm: StaffMovementsMessage =>
        updateState(StaffMovements(smm.staffMovements.map(sm => StaffMovement(
          sm.terminalName.getOrElse(""),
          sm.reason.getOrElse(""),
          MilliDate(sm.time.getOrElse(0)),
          sm.delta.getOrElse(0),
          UUID.fromString(sm.uUID.getOrElse("")),
          sm.queueName
        )).toList))
      case SnapshotOffer(_, snapshot: StaffMovementsState) => state = snapshot
    }

    val receiveCommand: Receive = {
      case GetState =>
        sender() ! state.events.headOption.getOrElse("")
      case staffMovements: StaffMovements =>
        val staffMovementsMessage = StaffMovementsMessage(staffMovements.staffMovements.map(sm =>
          StaffMovementMessage(
            Some(sm.terminalName),
            Some(sm.reason),
            Some(sm.time.millisSinceEpoch),
            Some(sm.delta),
            Some(sm.uUID.toString),
            sm.queue
          )
        ))
        persist(staffMovementsMessage) { (staffMovementsMessage: StaffMovementsMessage) =>
          context.system.eventStream.publish(staffMovementsMessage)
        }
        updateState(staffMovements)

    }
  }
