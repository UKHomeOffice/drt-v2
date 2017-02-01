package controllers

import akka.persistence._

case class ShiftsState(events: List[String] = Nil) {
  def updated(data: String): ShiftsState = copy(data :: events)
  def size: Int = events.length
  override def toString: String = events.reverse.toString
}

case object GetState

class ShiftsActor extends PersistentActor {

    override def persistenceId = "shifts-actor-id-1"

    var state = ShiftsState()

    def updateState(data: String): Unit = {
      state = state.updated(data)
    }

    def numEvents = state.size

    val receiveRecover: Receive = {
      case data: String  =>
        updateState(data)
      case SnapshotOffer(_, snapshot: ShiftsState) => state = snapshot
    }

    val receiveCommand: Receive = {
      case GetState =>
        sender() ! state.events.headOption.getOrElse("")
      case data: String =>
        persist(data) { event =>
          updateState(event)
          context.system.eventStream.publish(event)
        }
    }

  }
