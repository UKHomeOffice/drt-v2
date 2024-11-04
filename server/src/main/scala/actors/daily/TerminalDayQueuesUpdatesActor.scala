package actors.daily

import actors.StreamingJournalLike
import actors.serializers.PortStateMessageConversion
import akka.pattern.StatusReply
import akka.persistence.query.EventEnvelope
import akka.persistence.{SnapshotMetadata, SnapshotOffer}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.model.{CrunchMinute, TQM}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}


class TerminalDayQueuesUpdatesActor(year: Int,
                                    month: Int,
                                    day: Int,
                                    terminal: Terminal,
                                    val now: () => SDateLike,
                                    val journalType: StreamingJournalLike) extends StreamingUpdatesLike[CrunchMinute, TQM] {
  val persistenceId = f"terminal-queues-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"
  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  override def receiveCommand: Receive = myReceiveCommand orElse streamingUpdatesReceiveCommand

  def myReceiveCommand: Receive = {
    case EventEnvelope(_, _, _, CrunchMinutesMessage(minuteMessages)) =>
      updateState(minuteMessages)
      sender() ! StatusReply.Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), CrunchMinutesMessage(minuteMessages)) =>
      log.debug(s"Processing snapshot offer from ${SDate(ts).toISOString}")
      updateState(minuteMessages)

    case CrunchMinutesMessage(minuteMessages) =>
      updateState(minuteMessages)
  }

  def updatesFromMessages(minuteMessages: Seq[GeneratedMessage]): Seq[CrunchMinute] = minuteMessages.map {
    case msg: CrunchMinuteMessage => PortStateMessageConversion.crunchMinuteFromMessage(msg)
  }
}
