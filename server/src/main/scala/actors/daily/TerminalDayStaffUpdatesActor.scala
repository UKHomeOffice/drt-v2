package actors.daily

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.{PortStateMessageConversion, StreamingJournalLike}
import akka.persistence.query.EventEnvelope
import akka.persistence._
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}

class TerminalDayStaffBookmarkLookupActor(year: Int,
                                          month: Int,
                                          day: Int,
                                          terminal: Terminal,
                                          now: () => SDateLike,
                                          journalType: StreamingJournalLike,
                                          pointInTime: MillisSinceEpoch)
  extends TerminalDayStaffUpdatesActorLike(year, month, day, terminal, now, journalType) {

  override def recovery: Recovery = Recovery(SnapshotSelectionCriteria(maxTimestamp = pointInTime))

  override def receiveRecover: Receive = {
    case RecoveryCompleted =>
      startUpdatesStream(lastSequenceNr)

    case SnapshotOffer(SnapshotMetadata(_, seqNr, _), _) =>
      log.info(s"Starting updates stream from a SnapshotOffer with seqNr: $seqNr")
      startUpdatesStream(seqNr)
  }
}

abstract class TerminalDayStaffUpdatesActorLike(year: Int,
                                                month: Int,
                                                day: Int,
                                                terminal: Terminal,
                                                val now: () => SDateLike,
                                                val journalType: StreamingJournalLike) extends PersistentActor with StreamingUpdatesLike {
  val persistenceId = f"terminal-staff-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"
  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  var updates: Map[TM, StaffMinute] = Map[TM, StaffMinute]()

  override def receiveCommand: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.warn("Stream completed")

    case EventEnvelope(_, _, _, StaffMinutesMessage(minuteMessages)) =>
      updateState(minuteMessages)
      sender() ! Ack

    case GetAllUpdatesSince(sinceMillis) =>
      val response = updates.values.filter(_.lastUpdated.getOrElse(0L) >= sinceMillis) match {
        case someMinutes if someMinutes.nonEmpty => MinutesContainer(someMinutes)
        case _ => MinutesContainer.empty[StaffMinute, TM]
      }
      sender() ! response

    case u => log.error(s"Received unexpected ${u.getClass}")
  }

  def updateState(minuteMessages: Seq[StaffMinuteMessage]): Unit = {
    updates = updates ++ minuteMessages.map(PortStateMessageConversion.staffMinuteFromMessage).map(cm => (cm.key, cm))
    val thresholdExpiryMillis = expireBeforeMillis
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= thresholdExpiryMillis)
  }
}
