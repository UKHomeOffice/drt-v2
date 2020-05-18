package actors.daily

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.{PortStateMessageConversion, StreamingJournalLike}
import akka.persistence.query.EventEnvelope
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import services.SDate


class TerminalDayQueuesBookmarkLookupActor(year: Int,
                                          month: Int,
                                          day: Int,
                                          terminal: Terminal,
                                          now: () => SDateLike,
                                          journalType: StreamingJournalLike)
  extends TerminalDayQueuesUpdatesActorLike(year, month, day, terminal, now, journalType) {

  override def receiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), CrunchMinutesMessage(minuteMessages)) =>
      log.debug(s"Processing snapshot offer from ${SDate(ts).toISOString()}")
      updateState(minuteMessages)

    case CrunchMinutesMessage(minuteMessages) =>
      updateState(minuteMessages)

    case RecoveryCompleted =>
      log.info(s"Recovered. Starting updates stream")
      startUpdatesStream(lastSequenceNr)

    case unexpected =>
      log.error(s"Unexpected message: ${unexpected.getClass}")
  }
}

abstract class TerminalDayQueuesUpdatesActorLike(year: Int,
                                                 month: Int,
                                                 day: Int,
                                                 terminal: Terminal,
                                                 val now: () => SDateLike,
                                                 val journalType: StreamingJournalLike) extends PersistentActor with StreamingUpdatesLike {
  val persistenceId = f"terminal-queues-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"
  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  var updates: Map[TQM, CrunchMinute] = Map[TQM, CrunchMinute]()

  override def receiveCommand: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.warn("Stream completed")

    case EventEnvelope(_, _, _, CrunchMinutesMessage(minuteMessages)) =>
      updateState(minuteMessages)
      sender() ! Ack

    case GetAllUpdatesSince(sinceMillis) =>
      val response = updates.values.filter(_.lastUpdated.getOrElse(0L) >= sinceMillis) match {
        case someMinutes if someMinutes.nonEmpty => MinutesContainer(someMinutes)
        case _ => MinutesContainer.empty[CrunchMinute, TQM]
      }
      sender() ! response

    case x => log.warn(s"Received unexpected message ${x.getClass}")
  }

  def updateState(minuteMessages: Seq[CrunchMinuteMessage]): Unit = {
    updates = updates ++ minuteMessages.map(PortStateMessageConversion.crunchMinuteFromMessage).map(cm => (cm.key, cm))
    val thresholdExpiryMillis = expireBeforeMillis
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= thresholdExpiryMillis)
  }
}
