package actors.serializers

import uk.gov.homeoffice.drt.egates._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.EgateBanksUpdates._

object EgateBanksUpdatesMessageConversion {
  def removeEgateBanksUpdateToMessage(delete: DeleteEgateBanksUpdates): RemoveEgateBanksUpdateMessage =
    RemoveEgateBanksUpdateMessage(Option(delete.terminal.toString), Option(delete.millis))

  def egateBanksUpdateToMessage(egateBanksUpdate: EgateBanksUpdate): EgateBanksUpdateMessage =
    EgateBanksUpdateMessage(
      effectiveFrom = Option(egateBanksUpdate.effectiveFrom),
      banks = egateBanksUpdate.banks.map(bank => EgateBankMessage(bank.gates))
    )

  def egateBanksUpdatesToMessage(terminal: Terminal, egateBanksUpdates: EgateBanksUpdates): EgateBanksUpdatesMessage =
    EgateBanksUpdatesMessage(
      terminal = Option(terminal.toString),
      updates = egateBanksUpdates.updates.map(u => egateBanksUpdateToMessage(u))
    )

  def portUpdatesToMessage(state: PortEgateBanksUpdates): PortEgateBanksUpdatesMessage =
    PortEgateBanksUpdatesMessage(
      state.updatesByTerminal.map { case (terminal, egateBanksUpdates) =>
        egateBanksUpdatesToMessage(terminal, egateBanksUpdates)
      }.toSeq
    )

  def setEgateBanksUpdatesToMessage(updates: SetEgateBanksUpdate): SetEgateBanksUpdateMessage =
    SetEgateBanksUpdateMessage(
      Option(updates.terminal.toString),
      Option(updates.originalDate),
      Option(banksUpdateToMessage(updates.egateBanksUpdate))
    )

  def banksUpdateToMessage(update: EgateBanksUpdate): EgateBanksUpdateMessage = {
    val banks = update.banks.map(b => EgateBankMessage(b.gates))

    EgateBanksUpdateMessage(Option(update.effectiveFrom), banks)
  }

  def egateBanksUpdateFromMessage(message: EgateBanksUpdateMessage): Option[EgateBanksUpdate] =
    for {
      effectiveFrom <- message.effectiveFrom
    } yield {
      val banks = message.banks.map(b => EgateBank(b.gates.toIndexedSeq))
      EgateBanksUpdate(effectiveFrom, banks.toIndexedSeq)
    }

  def setEgateBanksUpdateFromMessage(msg: SetEgateBanksUpdateMessage): Option[SetEgateBanksUpdate] = {
    for {
      terminal <- msg.terminal
      originalDate <- msg.originalDate
      updates <- msg.update.flatMap(egateBanksUpdateFromMessage)
    } yield SetEgateBanksUpdate(Terminal(terminal), originalDate, updates)
  }

  def removeEgateBanksUpdateFromMessage(msg: RemoveEgateBanksUpdateMessage): Option[DeleteEgateBanksUpdates] = {
    for {
      terminal <- msg.terminal
      originalDate <- msg.date
    } yield DeleteEgateBanksUpdates(Terminal(terminal), originalDate)
  }

  def egateBanksUpdatesFromMessage(msg: EgateBanksUpdatesMessage): EgateBanksUpdates =
    EgateBanksUpdates(msg.updates.map(u => egateBanksUpdateFromMessage(u)).collect {
      case Some(update) => update
    }.toList)

  def portUpdatesFromMessage(msg: PortEgateBanksUpdatesMessage): PortEgateBanksUpdates =
    PortEgateBanksUpdates(
      msg.updates
        .map { u =>
          for {
            terminal <- u.terminal
          } yield {
            val updates = u.updates
              .map(egateBanksUpdateFromMessage)
              .collect {
                case Some(update) => update
              }
            (terminal, EgateBanksUpdates(updates.toList))
          }
        }
        .collect {
          case Some((t, u)) => (Terminal(t), u)
        }
        .toMap
    )

}
