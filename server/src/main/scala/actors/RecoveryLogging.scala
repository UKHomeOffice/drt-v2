package actors

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import com.trueaccord.scalapb.GeneratedMessage
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
    log.info(f"$megaBytes%.2fMB persisted since last snapshot")
  }
}

trait RecoveryActorLike extends PersistentActor with RecoveryLogging {
  val log: Logger

  val oneMegaByte: Int = 1024 * 1024
  val snapshotBytesThreshold: Int
  val maybeSnapshotInterval: Option[Int] = None
  var messagesPersistedSinceSnapshotCounter = 0
  var bytesSinceSnapshotCounter = 0

  def unknownMessage: PartialFunction[Any, Unit] = {
    case unknown => logUnknown(unknown)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit]

  def processSnapshotMessage: PartialFunction[Any, Unit]

  def playEventMessage: PartialFunction[Any, Unit] = processRecoveryMessage orElse unknownMessage

  def playSnapshotMessage: PartialFunction[Any, Unit] = processSnapshotMessage orElse unknownMessage

  def postRecoveryComplete(): Unit = Unit

  def postSaveSnapshot(): Unit = Unit

  def stateToMessage: GeneratedMessage

  def persistAndMaybeSnapshot(messageToPersist: GeneratedMessage): Unit = {
    persistMessage(messageToPersist)
    snapshotIfNeeded(stateToMessage)
  }

  def persistMessage(messageToPersist: GeneratedMessage): Unit = {
    persist(messageToPersist) { message =>
      val messageBytes = message.serializedSize
      log.info(s"Persisting $messageBytes bytes of ${message.getClass}")

      message match {
        case m: AnyRef =>
          context.system.eventStream.publish(m)
          bytesSinceSnapshotCounter += messageBytes
          messagesPersistedSinceSnapshotCounter += 1
          logPersistedBytesCounter(bytesSinceSnapshotCounter)
        case _ =>
          log.error("Message was not of type AnyRef and so could not be persisted")
      }
    }
  }

  def snapshotIfNeeded(stateToSnapshot: GeneratedMessage): Unit = {
    if (bytesSinceSnapshotCounter > snapshotBytesThreshold) {
      log.info(s"Snapshotting ${stateToSnapshot.serializedSize} bytes of ${stateToSnapshot.getClass}. Resetting byte counter to zero")
      saveSnapshot(stateToSnapshot)

      bytesSinceSnapshotCounter = 0
      messagesPersistedSinceSnapshotCounter = 0
      postSaveSnapshot()
    }
  }

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
