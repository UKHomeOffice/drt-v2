package actors.daily

import actors.GetState
import akka.actor.Props
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TQM}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import services.SDate


object TerminalDayQueuesActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props =
    Props(new TerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now))
}

class TerminalDayQueuesActor(year: Int,
                             month: Int,
                             day: Int,
                             terminal: Terminal,
                             val now: () => SDateLike) extends TerminalDayLikeActor(year, month, day, terminal, now) {
  override val typeForPersistenceId: String = "queues"

  log.info(s"PersistenceId: ${persistenceId}")

  var state: Map[TQM, CrunchMinute] = Map()

  import actors.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) => state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      log.debug(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
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

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

  override def receiveCommand: Receive = {
    case container: MinutesContainer[CrunchMinute, TQM] =>
      log.info(s"Received MinutesContainer (CrunchMinute) for persistence")
      updateAndPersistDiff(container)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! stateResponse

    case m => log.warn(s"Got unexpected message: $m")
  }

  private def updateAndPersistDiff(container: MinutesContainer[CrunchMinute, TQM]): Unit =
    diffFromMinutes(state, container.minutes) match {
      case noDifferences if noDifferences.isEmpty =>
        log.info("No differences. Nothing to persist")
        sender() ! true
      case differences =>
        state = updateStateFromDiff(state, differences)
        val messageToPersist = CrunchMinutesMessage(differences.map(crunchMinuteToMessage).toSeq)
        persistAndMaybeSnapshot(differences, messageToPersist)
    }

  private def stateResponse: Option[MinutesContainer[CrunchMinute, TQM]] = {
    if (state.nonEmpty) Option(MinutesContainer(state.values.toSet)) else None
  }
}
