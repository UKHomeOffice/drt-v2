package actors.daily

import akka.actor.Props
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute, StaffMinutes}
import drt.shared.{CrunchApi, TM, WsMessage}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}
import upickle.default.write


object TerminalDayStaffActor {
  def props(updateSubscribers: (UtcDate, String) => Unit)
           (terminal: Terminal,
            date: UtcDate,
            now: () => SDateLike,
           ): Props =
    Props(new TerminalDayStaffActor(date.year, date.month, date.day, terminal, now, None, updateSubscribers))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayStaffActor(date.year, date.month, date.day, terminal, now, Option(pointInTime), (_, _) => {}))
}

class TerminalDayStaffActor(year: Int,
                            month: Int,
                            day: Int,
                            terminal: Terminal,
                            val now: () => SDateLike,
                            maybePointInTime: Option[MillisSinceEpoch],
                            updateSubscribers: (UtcDate, String) => Unit,
                           )
  extends TerminalDayLikeActor[StaffMinute, TM, StaffMinuteMessage](year, month, day, terminal, now, maybePointInTime,
    updateSubscribers, vs => write(WsMessage("StaffMinutes", write(StaffMinutes(vs.toSeq))))) {

  override val persistenceIdType: String = "staff"

  import actors.serializers.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) => applyMessages(minuteMessages)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) => applyMessages(minuteMessages)
  }

  override val msgMinute: StaffMinuteMessage => MillisSinceEpoch = (msg: StaffMinuteMessage) => msg.getMinute
  override val msgLastUpdated: StaffMinuteMessage => MillisSinceEpoch = (msg: StaffMinuteMessage) => msg.getLastUpdated

  override def stateToMessage: GeneratedMessage = StaffMinutesMessage(state.values.map(staffMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[StaffMinute]): GeneratedMessage =
    StaffMinutesMessage(differences.map(m => staffMinuteToMessage(m.toMinute)).toSeq)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[StaffMinute, TM]): Boolean =
    container.contains(classOf[StaffMinute])

  override val valFromMessage: StaffMinuteMessage => StaffMinute = staffMinuteFromMessage
}
