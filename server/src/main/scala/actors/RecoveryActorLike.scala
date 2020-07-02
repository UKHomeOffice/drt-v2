package actors

import akka.actor.ActorRef
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import scalapb.GeneratedMessage
import org.slf4j.Logger

object Sizes {
  val oneMegaByte: Int = 1024 * 1024
}

trait RecoveryActorLike extends PersistentActor with RecoveryLogging {
  val log: Logger

  def now: () => SDateLike

  val recoveryStartMillis: MillisSinceEpoch

  val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  val maybeSnapshotInterval: Option[Int] = None
  var messagesPersistedSinceSnapshotCounter = 0
  var bytesSinceSnapshotCounter = 0

  def unknownMessage: PartialFunction[Any, Unit] = {
    case unknown => logUnknown(unknown)
  }

  def processRecoveryMessage: PartialFunction[Any, Unit]

  def processSnapshotMessage: PartialFunction[Any, Unit]

  def playRecoveryMessage: PartialFunction[Any, Unit] = processRecoveryMessage orElse unknownMessage

  def playSnapshotMessage: PartialFunction[Any, Unit] = processSnapshotMessage orElse unknownMessage

  def postRecoveryComplete(): Unit = {}

  def postSaveSnapshot(): Unit = {}

  def stateToMessage: GeneratedMessage

  def persistAndMaybeSnapshot(messageToPersist: GeneratedMessage, maybeAck: Option[(ActorRef, Any)] = None): Unit = {
    persist(messageToPersist) { message =>
      val messageBytes = message.serializedSize
      log.info(s"Persisting $messageBytes bytes of ${message.getClass}")

      context.system.eventStream.publish(message)
      bytesSinceSnapshotCounter += messageBytes
      messagesPersistedSinceSnapshotCounter += 1
      logCounters(bytesSinceSnapshotCounter, messagesPersistedSinceSnapshotCounter, snapshotBytesThreshold, maybeSnapshotInterval)

      snapshotIfNeeded(stateToMessage)

      maybeAck.foreach {
        case (replyTo, ackMsg) => replyTo ! ackMsg
      }
    }
  }

  def snapshotIfNeeded(stateToSnapshot: GeneratedMessage): Unit = if (shouldTakeSnapshot) takeSnapshot(stateToSnapshot)

  def takeSnapshot(stateToSnapshot: GeneratedMessage): Unit = {
    log.debug(s"Snapshotting ${stateToSnapshot.serializedSize} bytes of ${stateToSnapshot.getClass}. Resetting counters to zero")
    saveSnapshot(stateToSnapshot)

    bytesSinceSnapshotCounter = 0
    messagesPersistedSinceSnapshotCounter = 0
    postSaveSnapshot()
  }

  def shouldTakeSnapshot: Boolean = {
    val shouldSnapshotByCount = maybeSnapshotInterval.isDefined && messagesPersistedSinceSnapshotCounter >= maybeSnapshotInterval.get
    val shouldSnapshotByBytes = bytesSinceSnapshotCounter > snapshotBytesThreshold

    if (shouldSnapshotByCount) log.debug(f"Snapshot interval reached (${maybeSnapshotInterval.getOrElse(0)})")
    if (shouldSnapshotByBytes) log.debug(f"Snapshot bytes threshold reached (${snapshotBytesThreshold.toDouble / Sizes.oneMegaByte}%.2fMB)")

    shouldSnapshotByBytes || shouldSnapshotByCount
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(md, ss) =>
      logSnapshotOffer(md)
      playSnapshotMessage(ss)

    case RecoveryCompleted =>
      log.info(s"Recovery complete. Took ${now().millisSinceEpoch - recoveryStartMillis}ms. $messagesPersistedSinceSnapshotCounter messages replayed.")
      postRecoveryComplete()

    case event: GeneratedMessage =>
      bytesSinceSnapshotCounter += event.serializedSize
      messagesPersistedSinceSnapshotCounter += 1
      playRecoveryMessage(event)
  }
}
