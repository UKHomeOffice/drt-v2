package services.`export`

import akka.actor.Actor
import drt.shared.PortState
import services.crunch.deskrecs.GetStateForTerminalDateRange

class MockPortStateActor(portState: PortState) extends Actor {
  override def receive: Receive = {
    case GetStateForTerminalDateRange(_, _, _) => sender() ! portState
  }
}
