package actors.serializers

import drt.shared.NeboArrivals
import server.protobuf.messages.NeboPassengersMessage.{NeboArrivalMessages, NeboPassengersMessage}

object NeboArrivalMessageConversion {

  def stateToNeboArrivalMessages(state: NeboArrivals): NeboArrivalMessages = {
    NeboArrivalMessages(
      state.arrivalRedListPassengers.map {
        case (arrivalKey, urns) => NeboPassengersMessage(arrivalKey, urns.toList)
      }.toList)
  }

  def messageToNeboArrivalMessages(neboArrivalMessages: NeboArrivalMessages): NeboArrivals = {
    val arrivalRedListPassengers = for {
      n <- neboArrivalMessages.arrivalRedListPassengers
    } yield (n.arrivalKey -> n.urns)
    NeboArrivals(arrivalRedListPassengers = arrivalRedListPassengers.toMap.map { case (k, v) => k -> v.toSet })
  }

}
