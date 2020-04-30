package actors.daily

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.{PortStateMessageConversion, StreamingJournalLike}
import akka.NotUsed
import akka.actor.Actor
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike, TM}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}


class TerminalDayStaffUpdatesActor(year: Int,
                                   month: Int,
                                   day: Int,
                                   terminal: Terminal,
                                   now: () => SDateLike,
                                   val journalType: StreamingJournalLike,
                                   startingSequenceNr: Long) extends Actor {
  val persistenceId = f"terminal-staff-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  val queries: journalType.ReadJournalType = PersistenceQuery(context.system).readJournalFor[journalType.ReadJournalType](journalType.id)

  var updates: Map[TM, StaffMinute] = Map[TM, StaffMinute]()

  val cancellable: NotUsed = queries.eventsByPersistenceId(persistenceId, startingSequenceNr, Long.MaxValue)
    .runWith(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))

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

    case x => println(s"got $x")
  }

  def updateState(minuteMessages: Seq[StaffMinuteMessage]): Unit = {
    updates = updates ++ minuteMessages.map(PortStateMessageConversion.staffMinuteFromMessage).map(cm => (cm.key, cm))
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= expireBeforeMillis)
  }

  private def expireBeforeMillis: MillisSinceEpoch = now().millisSinceEpoch - MilliTimes.oneMinuteMillis
}
