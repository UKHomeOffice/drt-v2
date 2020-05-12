package actors.daily

import actors.{RecoveryActorLike, Sizes}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.SDate
import services.graphstages.Crunch

case object GetSummariesWithActualApi

abstract class TerminalDayLikeActor(year: Int,
                                    month: Int,
                                    day: Int,
                                    terminal: Terminal,
                                    now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d")

  val typeForPersistenceId: String

  override def persistenceId: String = f"terminal-$typeForPersistenceId-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
  val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  def persistAndMaybeSnapshot[A, B](differences: Iterable[MinuteLike[A, B]], messageToPersist: GeneratedMessage): Unit = {
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
