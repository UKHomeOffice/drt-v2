package actors.daily

import actors.GetState
import akka.actor.Props
import drt.shared.CrunchApi.{MinutesContainer, StaffMinute}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{StaffMinuteMessage, StaffMinutesMessage}


object TerminalDayStaffActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props =
    Props(new TerminalDayStaffActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now))
}

class TerminalDayStaffActor(year: Int,
                            month: Int,
                            day: Int,
                            terminal: Terminal,
                            val now: () => SDateLike) extends TerminalDayLikeActor(year, month, day, terminal, now) {
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
      case noDifferences if noDifferences.isEmpty => sender() ! true
      case differences =>
        state = updateStateFromDiff(state, differences)
        val messageToPersist = StaffMinutesMessage(differences.map(staffMinuteToMessage).toSeq)
        persistAndMaybeSnapshot(differences, messageToPersist)
    }

  private def stateResponse: Option[MinutesContainer[StaffMinute, TM]] = {
    if (state.nonEmpty) Option(MinutesContainer(state.values.toSet)) else None
  }
}
