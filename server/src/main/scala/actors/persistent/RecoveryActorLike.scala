package actors.persistent

import akka.actor.ActorRef
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotOffer}
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.Logger
import scalapb.GeneratedMessage

import scala.util.{Failure, Try}

object Sizes {
  val oneMegaByte: Int = 1024 * 1024
}

trait RecoveryActorLike extends PersistentActor with RecoveryLogging {
  val log: Logger

  def now: () => SDateLike

  val recoveryStartMillis: MillisSinceEpoch

  val snapshotBytesThreshold: Int
  val maybeSnapshotInterval: Option[Int]
  var messagesPersistedSinceSnapshotCounter = 0
  var bytesSinceSnapshotCounter = 0
  var maybeAckAfterSnapshot: Option[(ActorRef, Any)] = None

  def ackIfRequired(): Unit = {
    maybeAckAfterSnapshot.foreach {
      case (replyTo, msg) => replyTo ! msg
    }
    maybeAckAfterSnapshot = None
  }

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

  def persistAndMaybeSnapshot(message: GeneratedMessage): Unit = persistAndMaybeSnapshotWithAck(message, None)

  def persistAndMaybeSnapshotWithAck(messageToPersist: GeneratedMessage, maybeAck: Option[(ActorRef, Any)]): Unit = {
    persist(messageToPersist) { message =>
      val messageBytes = message.serializedSize
      log.debug(s"Persisting $messageBytes bytes of ${message.getClass}")

      context.system.eventStream.publish(message)
      bytesSinceSnapshotCounter += messageBytes
      messagesPersistedSinceSnapshotCounter += 1
      logCounters(bytesSinceSnapshotCounter, messagesPersistedSinceSnapshotCounter, snapshotBytesThreshold, maybeSnapshotInterval)

      if (shouldTakeSnapshot) {
        takeSnapshot(stateToMessage)
        maybeAckAfterSnapshot = maybeAck
      } else {
        maybeAck.foreach {
          case (replyTo, ackMsg) => replyTo ! ackMsg
        }
      }
    }
  }

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
      logRecoveryTime()
      postRecoveryComplete()

    case event: GeneratedMessage =>
      Try {
        bytesSinceSnapshotCounter += event.serializedSize
        messagesPersistedSinceSnapshotCounter += 1
        playRecoveryMessage(event)
      } match {
        case Failure(exception) =>
          log.error(s"Failed replay recovery message $event", exception)
        case _ =>
      }
  }

  private def logRecoveryTime(): Unit = {
    val tookMs: MillisSinceEpoch = now().millisSinceEpoch - recoveryStartMillis
    val message = s"Recovery complete. $messagesPersistedSinceSnapshotCounter messages replayed. Took ${tookMs}ms. "
    if (tookMs < 250L)
      log.debug(message)
    else if (tookMs < 5000L)
      log.warn(s"$message")
    else
      log.error(s"$message")
  }
}
