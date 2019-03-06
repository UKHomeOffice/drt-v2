package actors

import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import com.trueaccord.scalapb.GeneratedMessage
import org.slf4j.Logger

object Sizes {
  val oneMegaByte: Int = 1024 * 1024
}

trait RecoveryActorLike extends PersistentActor with RecoveryLogging {
  val log: Logger

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

  def postRecoveryComplete(): Unit =
    log.info("Recovery complete")
//    logCounters(bytesSinceSnapshotCounter, messagesPersistedSinceSnapshotCounter, snapshotBytesThreshold, maybeSnapshotInterval)

  def postSaveSnapshot(): Unit = Unit

  def stateToMessage: GeneratedMessage

  def persistAndMaybeSnapshot(messageToPersist: GeneratedMessage): Unit = {
    persist(messageToPersist) { message =>
      val messageBytes = message.serializedSize
      log.debug(s"Persisting $messageBytes bytes of ${message.getClass}")

      message match {
        case m: AnyRef =>
          context.system.eventStream.publish(m)
          bytesSinceSnapshotCounter += messageBytes
          messagesPersistedSinceSnapshotCounter += 1
          logCounters(bytesSinceSnapshotCounter, messagesPersistedSinceSnapshotCounter, snapshotBytesThreshold, maybeSnapshotInterval)

          snapshotIfNeeded(stateToMessage)
        case _ =>
          log.error("Message was not of type AnyRef and so could not be persisted")
      }
    }
  }

  def snapshotIfNeeded(stateToSnapshot: GeneratedMessage): Unit = if (shouldTakeSnapshot) {
    log.info(s"Snapshotting ${stateToSnapshot.serializedSize} bytes of ${stateToSnapshot.getClass}. Resetting counters to zero")
    saveSnapshot(stateToSnapshot)

    bytesSinceSnapshotCounter = 0
    messagesPersistedSinceSnapshotCounter = 0
    postSaveSnapshot()
  }

  def shouldTakeSnapshot: Boolean = {
    val shouldSnapshotByCount = maybeSnapshotInterval.isDefined && messagesPersistedSinceSnapshotCounter >= maybeSnapshotInterval.get
    val shouldSnapshotByBytes = bytesSinceSnapshotCounter > snapshotBytesThreshold

    if (shouldSnapshotByCount) log.info(f"Snapshot interval reached (${maybeSnapshotInterval.getOrElse(0)})")
    if (shouldSnapshotByBytes) log.info(f"Snapshot bytes threshold reached (${snapshotBytesThreshold.toDouble / Sizes.oneMegaByte}%.2fMB)")

    shouldSnapshotByBytes || shouldSnapshotByCount
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(md, ss) =>
      logSnapshotOffer(md)
      playSnapshotMessage(ss)

    case RecoveryCompleted =>
      postRecoveryComplete()

    case event =>
      playEventMessage(event)
  }
}
