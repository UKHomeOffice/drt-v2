package actors.daily

import actors.acking.AckingReceiver.Ack
import actors.{GetState, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.SnapshotOffer
import drt.shared.CrunchApi.{CrunchMinute, CrunchMinutes, MillisSinceEpoch}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import services.SDate
import services.graphstages.Crunch

object TerminalQueuesActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props =
    Props(classOf[TerminalQueuesActor], date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)
}

case object GetSummariesWithActualApi

class TerminalQueuesActor(year: Int,
                          month: Int,
                          day: Int,
                          terminal: Terminal,
                          val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = s"terminal-queues-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  var state: Map[TQM, CrunchMinute] = Map()

  private val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
  private val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  private val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  import actors.PortStateMessageConversion._

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      log.info(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ minuteMessagesToKeysAndMinutes(minuteMessages)
  }

  private def minuteMessagesToKeysAndMinutes(messages: Seq[CrunchMinuteMessage]): Iterable[(TQM, CrunchMinute)] = messages
    .filter { cmm =>
      val minuteMillis = cmm.minute.getOrElse(0L)
      firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
    }
    .map { cmm =>
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case SnapshotOffer(md, CrunchMinutesMessage(minuteMessages)) =>
      state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  override def receiveCommand: Receive = {
    case CrunchMinutes(minutes) =>
      log.info(s"Received CrunchMinutes for persistence")
      val diff = minutes.filter { cm =>
        state.get(cm.key) match {
          case Some(existingCm) if existingCm.equals(cm) => false
          case _ => true
        }
      }
      state = state ++ diff.map { cm => (cm.key, cm) }
      persistAndMaybeSnapshot(CrunchMinutesMessage(diff.map(crunchMinuteToMessage).toSeq))
      sender() ! Ack

    case GetState =>
      log.info(s"Received GetState")
      sender() ! stateResponse
  }

  private def stateResponse = {
    if (state.nonEmpty) Option(CrunchMinutes(state.values.toSet)) else None
  }
}

