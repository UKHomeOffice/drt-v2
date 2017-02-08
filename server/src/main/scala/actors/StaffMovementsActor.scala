package actors

import akka.persistence._
import spatutorial.shared.StaffMovement
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

    def numEvents = state.size

    val receiveRecover: Receive = {
      case data: StaffMovements  =>
        updateState(data)
      case SnapshotOffer(_, snapshot: StaffMovementsState) => state = snapshot
    }

    val receiveCommand: Receive = {
      case GetState =>
        sender() ! state.events.headOption.getOrElse("")
      case data: StaffMovements =>
        persist(data) { event =>
          updateState(event)
          context.system.eventStream.publish(event)
        }
    }
  }
