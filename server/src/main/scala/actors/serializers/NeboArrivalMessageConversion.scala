package actors.serializers

import drt.shared.NeboArrivals
import server.protobuf.messages.NeboPassengersMessage.{NeboArrivalMessage, NeboArrivalSnapshotMessage}

object NeboArrivalMessageConversion {

  def stateToNeboArrivalMessage(state: NeboArrivals): NeboArrivalMessage = {
    NeboArrivalMessage(urns = state.urns.toList)
  }

  def snapshotMessageToNeboArrival(neboArrivalSnapshotMessage: NeboArrivalSnapshotMessage): NeboArrivals = {
    NeboArrivals(urns = neboArrivalSnapshotMessage.urns.toSet)
  }

  def messageToNeboArrival(neboArrivalMessage: NeboArrivalMessage): NeboArrivals = {
    NeboArrivals(urns = neboArrivalMessage.urns.toSet)
  }
}
