package actors.daily

import actors.StreamingJournalLike
import actors.serializers.PortStateMessageConversion
import org.apache.pekko.pattern.StatusReply.Ack
import org.apache.pekko.persistence._
import org.apache.pekko.persistence.query.EventEnvelope
import drt.shared.CrunchApi.StaffMinute
import drt.shared.TM
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinuteRemovalMessage, StaffMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}


class TerminalDayStaffUpdatesActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   val now: () => SDateLike,
                                   val journalType: StreamingJournalLike) extends StreamingUpdatesLike[StaffMinute, TM] {
  val persistenceId = f"terminal-staff-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"
  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  override def receiveCommand: Receive = myReceiveCommand orElse streamingUpdatesReceiveCommand

  def myReceiveCommand: Receive = {
    case EventEnvelope(_, _, _, StaffMinutesMessage(minuteMessages, removalMessages)) =>
      updateState(minuteMessages, removalMessages)
      sender() ! Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), StaffMinutesMessage(minuteMessages, removalMessages)) =>
      log.debug(s"Processing snapshot offer from ${SDate(ts).toISOString}")
      updateState(minuteMessages, removalMessages)

    case StaffMinutesMessage(minuteMessages, removalMessages) =>
      updateState(minuteMessages, removalMessages)
  }

  def updatesFromMessages(minuteMessages: Seq[GeneratedMessage]): Seq[StaffMinute] = minuteMessages.map {
    case msg: StaffMinuteMessage => PortStateMessageConversion.staffMinuteFromMessage(msg)
  }

  def removalsFromMessages(removalMessages: Seq[GeneratedMessage]): Seq[TM] =
    removalMessages.map {
      case msg: StaffMinuteRemovalMessage => TM(terminal, SDate(msg.getMinute).millisSinceEpoch)
      case _ => throw new IllegalArgumentException("Unexpected message type for removal")
    }
}
