package actors.serializers

import drt.shared.NeboArrivals
import server.protobuf.messages.NeboPassengersMessage.{NeboArrivalMessages, NeboPassengersMessage}

object NeboArrivalMessageConversion {

  def stateToNeboArrivalMessages(state: NeboArrivals): NeboArrivalMessages = {
    NeboArrivalMessages(urns = state.urns.toList)
  }

  def messageToNeboArrivalMessages(neboArrivalMessages: NeboArrivalMessages): NeboArrivals = {
    NeboArrivals(urns = neboArrivalMessages.urns.toSet)
  }

}
