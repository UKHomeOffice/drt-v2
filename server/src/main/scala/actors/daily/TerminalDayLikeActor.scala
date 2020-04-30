package actors.daily

import actors.acking.AckingReceiver.Ack
import actors.{RecoveryActorLike, Sizes}
import akka.actor.ActorRef
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinuteLike, MinutesContainer, StaffMinute}
import drt.shared.{SDateLike, TM, TQM}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.SDate
import services.graphstages.Crunch

case object GetSummariesWithActualApi

case class MinutesState[A, B](minutes: MinutesContainer[A, B], bookmarkSeqNr: Long)

object TerminalDay {
  type TerminalDayBookmarks = Map[(Terminal, MillisSinceEpoch), Long]
}

abstract class TerminalDayLikeActor(year: Int,
                                    month: Int,
                                    day: Int,
                                    terminal: Terminal,
                                    now: () => SDateLike,
                                    maybePointInTime: Option[MillisSinceEpoch]) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d")

  val typeForPersistenceId: String

  override def persistenceId: String = f"terminal-$typeForPersistenceId-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.utcTimeZone)
  val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  override def recovery: Recovery = maybePointInTime match {
    case None => Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      val recovery = Recovery(fromSnapshot = criteria, replayMax = 10000)
      log.info(s"Recovery: $recovery")
      recovery
  }

  def persistAndMaybeSnapshot[A, B](differences: Iterable[MinuteLike[A, B]],
                                    messageToPersist: GeneratedMessage): Unit = {
    val replyTo = sender()
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
          replyTo ! MinutesContainer(differences)
        case _ =>
          log.error("Message was not of type AnyRef and so could not be persisted")
      }
    }
  }

  def diffFromMinutes[A, B](state: Map[B, A], minutes: Iterable[MinuteLike[A, B]]): Iterable[A] = {
    val nowMillis = now().millisSinceEpoch
    minutes
      .map(cm => state.get(cm.key) match {
        case None => Option(cm.toUpdatedMinute(nowMillis))
        case Some(existingCm) => cm.maybeUpdated(existingCm, nowMillis)
      })
      .collect { case Some(update) => update }
  }

  def updateStateFromDiff[A, B](state: Map[B, A], diff: Iterable[MinuteLike[A, B]]): Map[B, A] =
    state ++ diff.collect {
      case cm if firstMinuteMillis <= cm.minute && cm.minute < lastMinuteMillis => (cm.key, cm.toUpdatedMinute(cm.lastUpdated.getOrElse(0L)))
    }
}
