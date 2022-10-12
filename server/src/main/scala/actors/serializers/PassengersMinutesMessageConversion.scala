package actors.serializers

import drt.shared.CrunchApi.PassengersMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{PassengersMinuteMessage, PassengersMinutesMessage}

object PassengersMinutesMessageConversion {
  def passengerMinutesToMessage(px: Iterable[PassengersMinute]): PassengersMinutesMessage = {
    val minutes = px.map { p =>
      PassengersMinuteMessage(
        queueName = Option(p.queue.toString),
        minute = Option(p.minute),
        passengers = p.passengers.toSeq,
        lastUpdated = p.lastUpdated
      )
    }.toSeq

    PassengersMinutesMessage(minutes)
  }

  def passengersMinuteFromMessage(terminal: Terminal, pm: PassengersMinuteMessage): PassengersMinute = {
    val queue = Queue(pm.queueName.getOrElse("n/a"))
    val minute = pm.minute.get
    val passengers = pm.passengers
    PassengersMinute(terminal, queue, minute, passengers, pm.lastUpdated)
  }
}
