package actors

import actors.Sizes.oneMegaByte
import akka.persistence.{SaveSnapshotFailure, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import com.trueaccord.scalapb.GeneratedMessage
import drt.shared.Alert
import org.joda.time.DateTime
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.Alert.{AlertSnapshotMessage, Alert => ProtobufAlert}

case object DeleteAlerts

case class AlertsActor() extends RecoveryActorLike with PersistentDrtActor[Seq[Alert]] {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "alerts-store"

  override val snapshotBytesThreshold: Int = oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = None

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case alert: ProtobufAlert =>
      log.info(s"ALERTS GOT alert $alert")
      deserialise(alert).foreach(a => updateState(Seq(a)))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case smm: AlertSnapshotMessage =>
      log.info(s"ALERTS GOT alert snapshot $smm")
      updateState(smm.alerts.flatMap(deserialise))
  }

  //noinspection SpellCheckingInspection
  def deserialise(alert: ProtobufAlert): Option[Alert] = for {
    title <- alert.title
    message <- alert.message
    alertClass <- alert.alertClass
    expires <- alert.expires
    createdAt <- alert.createdAt
  } yield Alert(title, message, alertClass, expires, createdAt)

  def serialise(alert: Alert): ProtobufAlert = ProtobufAlert(Option(alert.title), Option(alert.message), Option(alert.expires), Option(alert.createdAt))

  def updateState(data: Seq[Alert]): Unit = state = state ++ data

  override def stateToMessage: GeneratedMessage = AlertSnapshotMessage(state.map(serialise))

  override var state: Seq[Alert] = initialState

  override def initialState: Seq[Alert] = Seq.empty[Alert]

  override def receiveCommand: Receive = {

    case alert: Alert =>
      log.info(s"Saving Alert $alert")
      updateState(Seq(alert))
      persistAndMaybeSnapshot(serialise(alert))
      sender() ! alert

    case GetState =>
      log.info(s"Received GetState request. Sending Alerts with ${state.size} alerts")
      sender() ! state.filter(a=> a.expires >= DateTime.now.getMillis)

    case DeleteAlerts =>
      deleteSnapshots(SnapshotSelectionCriteria(maxSequenceNr = lastSequenceNr))
      deleteMessages(lastSequenceNr)

      state = initialState
      sender() ! state

    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")

    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")

    case other =>
      log.error(s"Received unexpected message ${other.getClass}")

  }
}
