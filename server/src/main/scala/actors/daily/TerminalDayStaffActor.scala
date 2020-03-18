package actors.daily

import actors.acking.AckingReceiver.Ack
import actors.{ClearState, GetState}
import akka.actor.Props
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}


object TerminalDayStaffActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props =
    Props(classOf[TerminalDayStaffActor], date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)
}

class TerminalDayStaffActor(year: Int,
                            month: Int,
                            day: Int,
                            terminal: Terminal,
                            val now: () => SDateLike) extends TerminalDayLikeActor(year, month, day, terminal, now) {
  override val typeForPersistenceId: String = "staff"

  var state: Map[TM, StaffMinute] = Map()

  import actors.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) => state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) =>
      log.info(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ minuteMessagesToKeysAndMinutes(minuteMessages)
  }

  private def minuteMessagesToKeysAndMinutes(messages: Seq[StaffMinuteMessage]): Iterable[(TM, StaffMinute)] = messages
    .filter { cmm =>
      val minuteMillis = cmm.minute.getOrElse(0L)
      firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
    }
    .map { cmm =>
      val cm = staffMinuteFromMessage(cmm)
      (cm.key, cm)
    }

  override def stateToMessage: GeneratedMessage = StaffMinutesMessage(state.values.map(staffMinuteToMessage).toSeq)

  override def receiveCommand: Receive = {
    case MinutesContainer(minutes) =>
      log.info(s"Received StaffMinutes for persistence")
      val diff = minutes
        .collect { case cm: StaffMinute => cm }
        .filter { cm =>
          state.get(cm.key) match {
            case Some(existingCm) if existingCm.equals(cm) => false
            case _ => true
          }
        }
      state = state ++ diff.collect {
        case cm if firstMinuteMillis <= cm.minute && cm.minute < lastMinuteMillis => (cm.key, cm)
      }
      persistAndMaybeSnapshot(StaffMinutesMessage(diff.map(staffMinuteToMessage).toSeq))
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
