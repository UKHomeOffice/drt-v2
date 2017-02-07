package actors

import akka.persistence._
import spatutorial.shared.StaffMovement

case class StaffMovementsState(events: List[Seq[StaffMovement]] = Nil) {
  def updated(data: Seq[StaffMovement]): StaffMovementsState = copy(data :: events)
  def size: Int = events.length
  override def toString: String = events.reverse.toString
}

class StaffMovementsActor extends PersistentActor {

    override def persistenceId = "staff-movements-store"

    var state = StaffMovementsState()

    def updateState(data: Seq[StaffMovement]): Unit = {
      state = state.updated(data)
    }

    def numEvents = state.size

    val receiveRecover: Receive = {
      case data: Seq[StaffMovement]  =>
        updateState(data)
      case SnapshotOffer(_, snapshot: StaffMovementsState) => state = snapshot
    }

    val receiveCommand: Receive = {
      case GetState =>
        sender() ! state.events.headOption.getOrElse("")
      case data: Seq[StaffMovement] =>
        persist(data) { event =>
          updateState(event)
          context.system.eventStream.publish(event)
        }
    }
  }
