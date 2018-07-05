package actors

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import drt.shared.SDateLike
import org.slf4j.Logger
import services.SDate


trait RecoveryLogging {
  val log: Logger

  val prefix = "Recovery"

  def snapshotOfferLogMessage(md: SnapshotMetadata): String = s"$prefix received SnapshotOffer from ${SDate(md.timestamp).toISOString()}, sequence number ${md.sequenceNr}"

  def logSnapshotOffer(md: SnapshotMetadata): Unit = log.info(snapshotOfferLogMessage(md))

  def logSnapshotOffer(md: SnapshotMetadata, additionalInfo: String): Unit = log.info(s"${snapshotOfferLogMessage(md)} - $additionalInfo")

  def logEvent(event: Any): Unit = log.info(s"$prefix received event ${event.getClass}")

  def logRecoveryMessage(message: String): Unit = log.info(s"$prefix - $message")

  def logCompleted(): Unit = log.info(s"$prefix completed")

  def logPointInTimeCompleted(pit: SDateLike): Unit = log.info(s"$prefix completed to point-in-time ${pit.toISOString()}")

  def logUnknown(unknown: Any): Unit = log.warn(s"$prefix received unknown message ${unknown.getClass}")

  def logPersistedBytesCounter(bytes: Int): Unit = {
    val megaBytes = bytes.toDouble / (1024 * 1024)
    log.info(f"${megaBytes}%.2fMB persisted since last snapshot")
  }
}

trait RecoveryActorLike extends PersistentActor with RecoveryLogging {
  val log: Logger

  val oneMegaByte = 1024 * 1024
  var bytesSinceSnapshotCounter = 0

  def unknownMessage: PartialFunction[Any, Unit] = {
    case unknown => logUnknown(unknown)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit]

  def processSnapshotMessage: PartialFunction[Any, Unit]

  def playEventMessage: PartialFunction[Any, Unit] = processRecoveryMessage orElse unknownMessage

  def playSnapshotMessage: PartialFunction[Any, Unit] = processSnapshotMessage orElse unknownMessage

  def postRecoveryComplete(): Unit = Unit

  override def receiveRecover: Receive = {
    case SnapshotOffer(md, ss) =>
      logSnapshotOffer(md)
      playSnapshotMessage(ss)

    case RecoveryCompleted =>
      logCompleted()
      postRecoveryComplete()

    case event =>
      logEvent(event)
      playEventMessage(event)
      logPersistedBytesCounter(bytesSinceSnapshotCounter)
  }
}
