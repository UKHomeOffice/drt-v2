package actors.serializers

import scalapb.GeneratedMessage
import server.protobuf.messages.RedListUpdates._
import uk.gov.homeoffice.drt.redlist.{RedListUpdate, RedListUpdates, SetRedListUpdate}

object RedListUpdatesMessageConversion {
  def setUpdatesToMessage(updates: SetRedListUpdate): SetRedListUpdateMessage =
    SetRedListUpdateMessage(Option(updates.originalDate), Option(updateToMessage(updates.redListUpdate)))

  def updateToMessage(update: RedListUpdate): RedListUpdateMessage = {
    val additions = update.additions.map {
      case (n, c) => AdditionMessage(Option(n), Option(c))
    }.toSeq

    val removals = update.removals.map(n => RemovalMessage(Option(n)))

    RedListUpdateMessage(Option(update.effectiveFrom), additions, removals)
  }

  def additionsFromMessage(additions: Seq[AdditionMessage]): Map[String, String] =
    additions.collect {
      case AdditionMessage(Some(countryName), Some(countryCode)) => (countryName, countryCode)
    }.toMap

  def removalsFromMessage(removals: Seq[RemovalMessage]): List[String] =
    removals.collect {
      case RemovalMessage(Some(countryName)) => countryName
    }.toList

  def updateFromMessage(message: RedListUpdateMessage): Option[RedListUpdate] =
    for {
      effectiveFrom <- message.effectiveFrom
    } yield RedListUpdate(effectiveFrom, additionsFromMessage(message.additions), removalsFromMessage(message.removals))

  def setUpdatesFromMessage(msg: SetRedListUpdateMessage): Option[SetRedListUpdate] = {
    for {
      originalDate <- msg.originalDate
      updates <- msg.update.flatMap(updateFromMessage)
    } yield SetRedListUpdate(originalDate, updates)
  }

  def updatesFromMessage(msg: RedListUpdatesMessage): RedListUpdates =
    RedListUpdates(msg.updates.map(u => updateFromMessage(u)).collect {
      case Some(update) => (update.effectiveFrom, update)
    }.toMap)

}
