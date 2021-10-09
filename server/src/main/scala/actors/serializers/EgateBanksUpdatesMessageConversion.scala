package actors.serializers

import server.protobuf.messages.EgateBanksUpdates._
import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, SetEgateBanksUpdate}

object EgateBanksUpdatesMessageConversion {
  def setUpdatesToMessage(updates: SetEgateBanksUpdate): SetEgateBanksUpdateMessage =
    SetEgateBanksUpdateMessage(Option(updates.originalDate), Option(banksUpdateToMessage(updates.egateBanksUpdate)))

  def banksUpdateToMessage(update: EgateBanksUpdate): EgateBanksUpdateMessage = {
    val banks = update.banks.map(b => EgateBankMessage(b.gates))

    EgateBanksUpdateMessage(Option(update.effectiveFrom), banks)
  }

  def updateFromMessage(message: EgateBanksUpdateMessage): Option[EgateBanksUpdate] =
    for {
      effectiveFrom <- message.effectiveFrom
    } yield {
      val banks = message.banks.map(b => EgateBank(b.gates.toIndexedSeq))
      EgateBanksUpdate(effectiveFrom, banks.toIndexedSeq)
    }

  def setUpdatesFromMessage(msg: SetEgateBanksUpdateMessage): Option[SetEgateBanksUpdate] = {
    for {
      originalDate <- msg.originalDate
      updates <- msg.update.flatMap(updateFromMessage)
    } yield SetEgateBanksUpdate(originalDate, updates)
  }

  def updatesFromMessage(msg: EgateBanksUpdatesMessage): EgateBanksUpdates =
    EgateBanksUpdates(msg.updates.map(u => updateFromMessage(u)).collect {
      case Some(update) => update
    }.toList)

}
