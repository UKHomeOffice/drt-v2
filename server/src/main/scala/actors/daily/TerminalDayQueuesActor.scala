package actors.daily

import akka.actor.Props
import drt.shared.CrunchApi
import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.Future


object TerminalDayQueuesActor {
  def props(maybeUpdateLiveView: Option[(UtcDate, Iterable[CrunchMinute]) => Future[Unit]])
           (terminal: Terminal,
            date: UtcDate,
            now: () => SDateLike,
           ): Props =
    Props(new TerminalDayQueuesActor(date.year, date.month, date.day, terminal, now, None, maybeUpdateLiveView))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayQueuesActor(date.year, date.month, date.day, terminal, now, Option(pointInTime), None))
}

class TerminalDayQueuesActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike,
                             maybePointInTime: Option[MillisSinceEpoch],
                             override val onUpdate: Option[(UtcDate, Iterable[CrunchMinute]) => Future[Unit]],
                            ) extends
  TerminalDayLikeActor[CrunchMinute, TQM, CrunchMinuteMessage](year, month, day, terminal, now, maybePointInTime) {
  override val persistenceIdType: String = "queues"

  import actors.serializers.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => applyMessages(minuteMessages)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => applyMessages(minuteMessages)
  }

  override val msgMinute: CrunchMinuteMessage => MillisSinceEpoch = (msg: CrunchMinuteMessage) => msg.getMinute
  override val msgLastUpdated: CrunchMinuteMessage => MillisSinceEpoch = (msg: CrunchMinuteMessage) => msg.getLastUpdated

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[CrunchMinute]): GeneratedMessage =
    CrunchMinutesMessage(differences.map(m => crunchMinuteToMessage(m.toMinute)).toSeq)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[CrunchMinute, TQM]): Boolean =
    container.contains(classOf[DeskRecMinute])

  override val valFromMessage: CrunchMinuteMessage => CrunchMinute = crunchMinuteFromMessage
}
