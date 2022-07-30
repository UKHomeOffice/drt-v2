package actors.serializers

import drt.shared.{NeboArrivals, RedListPassengers}
import uk.gov.homeoffice.drt.protobuf.messages.NeboPassengersMessage.{NeboArrivalMessage, NeboArrivalSnapshotMessage}

object NeboArrivalMessageConversion {


  def redListPassengersToNeboArrivalMessage(redListPassengers: RedListPassengers): NeboArrivalMessage = {
    NeboArrivalMessage(urns = redListPassengers.urns.toList)
  }

  def stateToNeboArrivalSnapshotMessage(state: NeboArrivals): NeboArrivalSnapshotMessage = {
    NeboArrivalSnapshotMessage(urns = state.urns.toList)
  }

  def snapshotMessageToNeboArrival(neboArrivalSnapshotMessage: NeboArrivalSnapshotMessage): NeboArrivals = {
    NeboArrivals(urns = neboArrivalSnapshotMessage.urns.toSet)
  }

  def messageToNeboArrival(neboArrivalMessage: NeboArrivalMessage): NeboArrivals = {
    NeboArrivals(urns = neboArrivalMessage.urns.toSet)
  }
}
