package actors.daily

import drt.shared.CrunchApi.{MillisSinceEpoch, StaffMinute}
import drt.shared.{CrunchApi, TM}
import org.apache.pekko.actor.Props
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinuteRemovalMessage, StaffMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.collection.mutable


object TerminalDayStaffActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayStaffActor(date, terminal, now, None))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayStaffActor(date, terminal, now, Option(pointInTime)))
}

class TerminalDayStaffActor(utcDate: UtcDate,
                            terminal: Terminal,
                            val now: () => SDateLike,
                            maybePointInTime: Option[MillisSinceEpoch],
                           )
  extends TerminalDayLikeActor[StaffMinute, TM, StaffMinuteMessage, StaffMinuteRemovalMessage](utcDate, terminal, now, maybePointInTime) {

  override val persistenceIdType: String = "staff"

  import actors.serializers.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages, removals) => applyMessages(minuteMessages, removals)
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages, removals) => applyMessages(minuteMessages, removals)
  }

  override val msgMinute: StaffMinuteMessage => MillisSinceEpoch = (msg: StaffMinuteMessage) => msg.getMinute
  override val msgLastUpdated: StaffMinuteMessage => MillisSinceEpoch = (msg: StaffMinuteMessage) => msg.getLastUpdated

  override def stateToMessage: GeneratedMessage = StaffMinutesMessage(state.values.map(staffMinuteToMessage).toSeq)

  override def containerToMessage(differences: Iterable[StaffMinute], removals: Iterable[TM]): GeneratedMessage = {
    val updates = differences.map(m => staffMinuteToMessage(m.toMinute)).toSeq
    val removalMsgs = removals.map(r => StaffMinuteRemovalMessage(terminalName = Option(terminal.toString), minute = Option(r.minute))).toSeq
    StaffMinutesMessage(updates, removalMsgs)
  }

  override def shouldSendEffectsToSubscriber(container: CrunchApi.MinutesContainer[StaffMinute, TM]): Boolean =
    container.contains(classOf[StaffMinute])

  override val valFromMessage: StaffMinuteMessage => StaffMinute = staffMinuteFromMessage

  override val indexFromMessage: StaffMinuteRemovalMessage => TM = (pm: StaffMinuteRemovalMessage) => TM(terminal, pm.getMinute)

  override def removalsMinutes(state: mutable.Map[TM, StaffMinute]): Iterable[TM] = Seq.empty
}
