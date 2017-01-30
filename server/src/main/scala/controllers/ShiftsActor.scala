package controllers

import akka.persistence._

case class Cmd(data: String)
case class Evt(data: String)

case class ExampleState(events: List[String] = Nil) {
  def updated(evt: Evt): ExampleState = copy(evt.data :: events)
  def size: Int = events.length
  override def toString: String = events.reverse.toString
}

class ShiftsActor extends PersistentActor {

    override def persistenceId = "sample-id-1"

    var state = ExampleState()

    def updateState(event: Evt): Unit = {
      println(s"event data $event")
      state = state.updated(event)
    }

    def numEvents = state.size

    val receiveRecover: Receive = {
      case evt: Evt                                 => updateState(evt)
      case SnapshotOffer(_, snapshot: ExampleState) => state = snapshot
    }

    val receiveCommand: Receive = {
      case Cmd(data) =>
        persist(Evt(s"${data}-${numEvents}"))(updateState)
        persist(Evt(s"${data}-${numEvents + 1}")) { event =>
          updateState(event)
          context.system.eventStream.publish(event)
        }
      case "snap"  => saveSnapshot(state)
      case "print" => println(state)
    }

  }
