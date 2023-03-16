package actors.daily

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.Ack
import actors.serializers.PortStateMessageConversion
import akka.persistence._
import akka.persistence.query.EventEnvelope
import drt.shared.CrunchApi.StaffMinute
import drt.shared.TM
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}
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
    case EventEnvelope(_, _, _, StaffMinutesMessage(minuteMessages)) =>
      updateState(minuteMessages)
      sender() ! Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), StaffMinutesMessage(minuteMessages)) =>
      log.debug(s"Processing snapshot offer from ${SDate(ts).toISOString}")
      updateState(minuteMessages)

    case StaffMinutesMessage(minuteMessages) =>
      updateState(minuteMessages)
  }

  def updatesFromMessages(minuteMessages: Seq[GeneratedMessage]): Seq[StaffMinute] = minuteMessages.map {
    case msg: StaffMinuteMessage => PortStateMessageConversion.staffMinuteFromMessage(msg)
  }
}
