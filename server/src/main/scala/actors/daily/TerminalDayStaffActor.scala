package actors.daily

import actors.GetState
import akka.actor.Props
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}


object TerminalDayStaffActor {
  def props(terminal: Terminal, date: SDateLike, now: () => SDateLike): Props =
    Props(new TerminalDayStaffActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now, None))
}

class TerminalDayStaffActor(year: Int,
                            month: Int,
                            day: Int,
                            terminal: Terminal,
                            val now: () => SDateLike,
                            maybePointInTime: Option[MillisSinceEpoch]) extends TerminalDayLikeActor(year, month, day, terminal, now, maybePointInTime) {
  override val typeForPersistenceId: String = "staff"

  var state: Map[TM, StaffMinute] = Map()

  import actors.PortStateMessageConversion._

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) => state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case StaffMinutesMessage(minuteMessages) =>
      log.debug(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ minuteMessagesToKeysAndMinutes(minuteMessages)
  }

  private def minuteMessagesToKeysAndMinutes(messages: Seq[StaffMinuteMessage]): Iterable[(TM, StaffMinute)] = messages
    .filter { cmm =>
      val minuteMillis = cmm.minute.getOrElse(0L)
      firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
    }
    .map { cmm =>
      val cm = staffMinuteFromMessage(cmm)
      (cm.key, cm)
    }

  override def stateToMessage: GeneratedMessage = StaffMinutesMessage(state.values.map(staffMinuteToMessage).toSeq)

  override def receiveCommand: Receive = {
    case container: MinutesContainer[StaffMinute, TM] =>
      log.debug(s"Received MinutesContainer for persistence")
      updateAndPersistDiff(container)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! stateResponse

    case m => log.warn(s"Got unexpected message: $m")
  }

  private def updateAndPersistDiff(container: MinutesContainer[StaffMinute, TM]): Unit =
    diffFromMinutes(state, container.minutes) match {
      case noDifferences if noDifferences.isEmpty =>
        log.info("No differences. Nothing to persist")
        sender() ! MinutesContainer.empty[StaffMinute, TM]
      case differences =>
        state = updateStateFromDiff(state, differences)
        val messageToPersist = StaffMinutesMessage(differences.map(staffMinuteToMessage).toSeq)
        persistAndMaybeSnapshot(differences, messageToPersist)
    }

  private def stateResponse: Option[MinutesState[StaffMinute, TM]] = {
    if (state.nonEmpty) Option(MinutesState(MinutesContainer(state.values.toSet), lastSequenceNr)) else None
  }
}
