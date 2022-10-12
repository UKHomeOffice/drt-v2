package actors.daily

import akka.actor.Props
import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import drt.shared.{CrunchApi, TM}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}


object TerminalDayStaffActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayStaffActor(date.year, date.month, date.day, terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayStaffActor(date.year, date.month, date.day, terminal, now, Option(pointInTime)))
}

class TerminalDayStaffActor(year: Int,
                            month: Int,
                            day: Int,
                            terminal: Terminal,
                            val now: () => SDateLike,
                            maybePointInTime: Option[MillisSinceEpoch]) extends TerminalDayLikeActor[StaffMinute, TM](year, month, day, terminal, now, maybePointInTime) {
  override val persistenceIdType: String = "staff"

  import actors.serializers.PortStateMessageConversion._

  val shouldApply: StaffMinuteMessage => Boolean = maybePointInTime match {
    case None =>
      (cmm: StaffMinuteMessage) => {
        val m = cmm.minute.getOrElse(0L)
        firstMinuteMillis <= m && m <= lastMinuteMillis
      }
    case Some(pit) =>
      (cmm: StaffMinuteMessage) => {
        val m = cmm.minute.getOrElse(0L)
        cmm.lastUpdated.getOrElse(0L) <= pit && firstMinuteMillis <= m && m <= lastMinuteMillis
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) => applyMessages(minuteMessages, shouldApply, staffMinuteFromMessage)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) => applyMessages(minuteMessages, shouldApply, staffMinuteFromMessage)
  }

  override def stateToMessage: GeneratedMessage = StaffMinutesMessage(state.values.map(staffMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[StaffMinute]): GeneratedMessage =
    StaffMinutesMessage(differences.map(m => staffMinuteToMessage(m.toMinute)).toSeq)

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[StaffMinute, TM]): Boolean =
    container.contains(classOf[StaffMinute])
}
