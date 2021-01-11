package actors.daily

import akka.actor.Props
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}


object TerminalDayQueuesActor {
  def props(terminal: Terminal, date: SDateLike, now: () => SDateLike): Props =
    Props(new TerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: SDateLike, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now, Option(pointInTime)))
}

class TerminalDayQueuesActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike,
                             maybePointInTime: Option[MillisSinceEpoch]) extends TerminalDayLikeActor[CrunchMinute, TQM](year, month, day, terminal, now, maybePointInTime) {
  override val typeForPersistenceId: String = "queues"

  import actors.serializers.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      log.debug(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ updatesToApply(minuteMessagesToKeysAndMinutes(minuteMessages))
  }

  private def minuteMessagesToKeysAndMinutes(messages: Seq[CrunchMinuteMessage]): Iterable[(TQM, CrunchMinute)] =
    messages
      .filter { cmm =>
        val minuteMillis = cmm.minute.getOrElse(0L)
        firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
      }
      .map { cmm =>
        val cm = crunchMinuteFromMessage(cmm)
        (cm.key, cm)
      }

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[CrunchMinute]): GeneratedMessage =
    CrunchMinutesMessage(differences.map(m => crunchMinuteToMessage(m.toMinute)).toSeq)
}
