package actors.daily

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.{PortStateMessageConversion, StreamingJournalLike}
import akka.persistence._
import akka.persistence.query.EventEnvelope
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}
import services.SDate

class TerminalDayStaffBookmarkLookupActor(year: Int,
                                          month: Int,
                                          day: Int,
                                          terminal: Terminal,
                                          now: () => SDateLike,
                                          journalType: StreamingJournalLike)
  extends TerminalDayStaffUpdatesActorLike(year, month, day, terminal, now, journalType) {

  override def receiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), StaffMinutesMessage(minuteMessages)) =>
      log.debug(s"Processing snapshot offer from ${SDate(ts).toISOString()}")
      updateState(minuteMessages)

    case StaffMinutesMessage(minuteMessages) =>
      updateState(minuteMessages)

    case RecoveryCompleted =>
      log.info(s"Recovered. Starting updates stream")
      startUpdatesStream(lastSequenceNr)

    case unexpected =>
      log.error(s"Unexpected message: ${unexpected.getClass}")
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
