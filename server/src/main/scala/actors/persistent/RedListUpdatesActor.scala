package actors.persistent

import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.Sizes.oneMegaByte
import actors.persistent.staffing.GetState
import actors.serializers.RedListUpdatesMessageConversion
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.redlist.{DeleteRedListUpdates, RedList, RedListUpdates, SetRedListUpdate}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.RedListUpdates.{RedListUpdatesMessage, RemoveUpdateMessage, SetRedListUpdateMessage}

class RedListUpdatesActor(val now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[RedListUpdates] {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "red-list-updates"

  override val snapshotBytesThreshold: Int = oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = None

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case updates: SetRedListUpdateMessage =>
      RedListUpdatesMessageConversion.setUpdatesFromMessage(updates).map(u => state = state.update(u))

    case delete: RemoveUpdateMessage =>
      delete.date.map(effectiveFrom => state = state.remove(effectiveFrom))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case smm: RedListUpdatesMessage =>
      state = RedListUpdatesMessageConversion.updatesFromMessage(smm)
  }

  override def stateToMessage: GeneratedMessage =
    RedListUpdatesMessage(state.updates.values.map(RedListUpdatesMessageConversion.updateToMessage).toList)

  var state: RedListUpdates = initialState

  override def initialState: RedListUpdates = RedListUpdates(RedList.redListChanges)

  override def receiveCommand: Receive = {
    case updates: SetRedListUpdate =>
      log.info(s"Saving RedListUpdates $updates")
      state = state.update(updates)
      persistAndMaybeSnapshot(RedListUpdatesMessageConversion.setUpdatesToMessage(updates))
      sender() ! updates

    case GetState =>
      log.debug(s"Received GetState request. Sending RedListUpdates with ${state.updates.size} update sets")
      sender() ! state

    case delete: DeleteRedListUpdates =>
      state = state.remove(delete.millis)
      persistAndMaybeSnapshot(RemoveUpdateMessage(Option(delete.millis)))
      sender() ! delete

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case DeleteSnapshotsSuccess(_) =>

    case DeleteMessagesSuccess(_) =>

    case DeleteSnapshotsFailure(_, t) => log.error(s"Failed to delete updatess snapshots", t)

    case DeleteMessagesFailure(_, t) => log.error(s"Failed to delete updatess messages", t)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message ${unexpected.getClass}")
  }
}
