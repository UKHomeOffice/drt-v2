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

  val shouldApply: CrunchMinuteMessage => Boolean = maybePointInTime match {
    case None =>
      (cmm: CrunchMinuteMessage) => {
        val m = cmm.minute.getOrElse(0L)
        firstMinuteMillis <= m && m <= lastMinuteMillis
      }
    case Some(pit) =>
      (cmm: CrunchMinuteMessage) => {
        val m = cmm.minute.getOrElse(0L)
        cmm.lastUpdated.getOrElse(0L) <= pit && firstMinuteMillis <= m && m <= lastMinuteMillis
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => applyMessages(minuteMessages, shouldApply, crunchMinuteFromMessage)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      if (messageRecoveryStartMillis.isEmpty) messageRecoveryStartMillis = Option(now().millisSinceEpoch)
      val apply = minuteMessages.nonEmpty && (maybePointInTime match {
        case None => true
        case Some(pit) =>
          minuteMessages.map(_.lastUpdated.getOrElse(0L)).max <= pit
      })
      if (apply) {
        applyMessages(minuteMessages, shouldApply, crunchMinuteFromMessage)
      } else messagesPersistedSinceSnapshotCounter -= 1
  }

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[CrunchMinute]): GeneratedMessage =
    CrunchMinutesMessage(differences.map(m => crunchMinuteToMessage(m.toMinute)).toSeq)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[CrunchMinute, TQM]): Boolean =
    container.contains(classOf[DeskRecMinute])
}
