package actors.daily

import akka.actor.Props
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MillisSinceEpoch}
import drt.shared.{CrunchApi, TQM}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}


object TerminalDayQueuesActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayQueuesActor(date.year, date.month, date.day, terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayQueuesActor(date.year, date.month, date.day, terminal, now, Option(pointInTime)))
}

class TerminalDayQueuesActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike,
                             maybePointInTime: Option[MillisSinceEpoch]) extends TerminalDayLikeActor[CrunchMinute, TQM](year, month, day, terminal, now, maybePointInTime) {
  override val persistenceIdType: String = "queues"

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

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[CrunchMinute, TQM]): Boolean =
    container.contains(classOf[DeskRecMinute])
}
