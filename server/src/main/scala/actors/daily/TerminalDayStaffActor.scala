package actors.daily

import akka.actor.Props
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import drt.shared.{CrunchApi, TM}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}


object TerminalDayStaffActor {
  def props(processingRequests: (Terminal, Set[UtcDate]) => Set[TerminalUpdateRequest])
           (terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayStaffActor(date.year, date.month, date.day, terminal, now, None, processingRequests))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayStaffActor(date.year, date.month, date.day, terminal, now, Option(pointInTime), (_, _) => Set.empty))
}

class TerminalDayStaffActor(year: Int,
                            month: Int,
                            day: Int,
                            terminal: Terminal,
                            val now: () => SDateLike,
                            maybePointInTime: Option[MillisSinceEpoch],
                            processingRequests: (Terminal, Set[UtcDate]) => Set[TerminalUpdateRequest]
                           )
  extends TerminalDayLikeActor[StaffMinute, TM, StaffMinuteMessage](year, month, day, terminal, now, processingRequests, maybePointInTime) {

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
