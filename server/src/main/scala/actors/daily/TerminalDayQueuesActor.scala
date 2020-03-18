package actors.daily

import actors.acking.AckingReceiver.Ack
import actors.{ClearState, GetState}
import akka.actor.Props
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}


object TerminalDayQueuesActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props =
    Props(new TerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now))
}

class TerminalDayQueuesActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike) extends TerminalDayLikeActor(year, month, day, terminal, now) {
  override val typeForPersistenceId: String = "queues"

  var state: Map[TQM, CrunchMinute] = Map()

  import actors.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      log.info(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ minuteMessagesToKeysAndMinutes(minuteMessages)
  }

  private def minuteMessagesToKeysAndMinutes(messages: Seq[CrunchMinuteMessage]): Iterable[(TQM, CrunchMinute)] = messages
    .filter { cmm =>
      val minuteMillis = cmm.minute.getOrElse(0L)
      firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
    }
    .map { cmm =>
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  def collect[X]: PartialFunction[Any, X] = {
    case cm: X => cm
  }

  override def receiveCommand: Receive = {
    case MinutesContainer(minutes) =>
      log.info(s"Received MinutesContainer for persistence")
      val diff = minutes
        .collect { collect[CrunchMinute] }
        .filter { cm =>
          state.get(cm.key) match {
            case Some(existingCm) if existingCm.equals(cm) => false
            case _ => true
          }
        }
      state = state ++ diff.collect {
        case cm if firstMinuteMillis <= cm.minute && cm.minute < lastMinuteMillis => (cm.key, cm)
      }
      persistAndMaybeSnapshot(CrunchMinutesMessage(diff.map(crunchMinuteToMessage).toSeq))
      sender() ! Ack

    case GetState =>
      log.info(s"Received GetState")
      sender() ! stateResponse

    case ClearState =>
      log.info(s"Received ClearState")
      state = Map()
      sender() ! Ack

    case m => log.warn(s"Got unexpected message: $m")
  }

  private def stateResponse: Option[MinutesContainer] = {
    if (state.nonEmpty) Option(MinutesContainer(state.values.toSet)) else None
  }
}
