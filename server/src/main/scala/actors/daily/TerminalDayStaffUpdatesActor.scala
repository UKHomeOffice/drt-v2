package actors.daily

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.{PortStateMessageConversion, StreamingJournalLike}
import akka.NotUsed
import akka.actor.Actor
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import akka.stream.scaladsl.{Keep, RunnableGraph, Sink, Source}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike, TM}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}


class TerminalDayStaffUpdatesActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   val now: () => SDateLike,
                                   val journalType: StreamingJournalLike,
                                   val startingSequenceNr: Long) extends StreamingUpdatesLike {
  val persistenceId = f"terminal-staff-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"
  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  var updates: Map[TM, StaffMinute] = Map[TM, StaffMinute]()

  override def receive: Receive = {
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

    case u =>
      log.error(s"Received unexpected ${u.getClass}")
  }

  def updateState(minuteMessages: Seq[StaffMinuteMessage]): Unit = {
    updates = updates ++ minuteMessages.map(PortStateMessageConversion.staffMinuteFromMessage).map(cm => (cm.key, cm))
    val thresholdExpiryMillis = expireBeforeMillis
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= thresholdExpiryMillis)
  }
}
