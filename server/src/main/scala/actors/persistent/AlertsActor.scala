package actors.persistent

import actors.acking.AckingReceiver.StreamCompleted
import actors.persistent.Sizes.oneMegaByte
import actors.persistent.staffing.GetState
import actors.serializers.AlertMessageConversion
import akka.persistence._
import drt.shared.Alert
import drt.shared.CrunchApi.MillisSinceEpoch
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.protobuf.messages.Alert.{AlertSnapshotMessage, Alert => ProtobufAlert}
import uk.gov.homeoffice.drt.time.SDateLike

case object DeleteAlerts

class AlertsActor(val now: () => SDateLike) extends RecoveryActorLike with PersistentDrtActor[Seq[Alert]] {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "alerts-store"

  override val snapshotBytesThreshold: Int = oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = None

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case alert: ProtobufAlert => AlertMessageConversion.alertFromMessage(alert).foreach(a => updateState(Seq(a)))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case smm: AlertSnapshotMessage => updateState(smm.alerts.flatMap(AlertMessageConversion.alertFromMessage))
  }

  def updateState(data: Seq[Alert]): Unit = state = state ++ data

  override def stateToMessage: GeneratedMessage = AlertSnapshotMessage(state.map(AlertMessageConversion.alertToMessage))

  var state: Seq[Alert] = initialState

  override def initialState: Seq[Alert] = Seq.empty[Alert]

  override def receiveCommand: Receive = {

    case alert: Alert =>
      log.info(s"Saving Alert $alert")
      updateState(Seq(alert))
      persistAndMaybeSnapshot(AlertMessageConversion.alertToMessage(alert))
      sender() ! alert

    case GetState =>
      log.debug(s"Received GetState request. Sending Alerts with ${state.size} alerts")
      sender() ! state.filter(a=> a.expires >= DateTime.now.getMillis)

    case DeleteAlerts =>
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = lastSequenceNr))
      deleteMessages(lastSequenceNr)

      state = initialState
      sender() ! state

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.error(s"Save snapshot failure: $md", cause)

    case DeleteSnapshotsSuccess(_) =>

    case DeleteMessagesSuccess(_) =>

    case DeleteSnapshotsFailure(_, t) => log.error(s"Failed to delete alerts snapshots", t)

    case DeleteMessagesFailure(_, t) => log.error(s"Failed to delete alerts messages", t)

    case StreamCompleted => log.warn("Received shutdown")

    case unexpected => log.error(s"Received unexpected message ${unexpected.getClass}")
  }
}
