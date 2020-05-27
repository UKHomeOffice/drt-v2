package actors.daily

import actors.{RecoveryActorLike, Sizes}
import akka.persistence.{Recovery, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}
import actors.PortStateMessageConversion.crunchMinuteToMessage
import actors.{GetState, RecoveryActorLike, Sizes}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinuteLike, MinutesContainer}
import drt.shared.{SDateLike, TQM, WithTimeAccessor}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, WithTimeAccessor}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import services.SDate
import services.graphstages.Crunch

case object GetSummariesWithActualApi

case class MinutesState[A, B <: WithTimeAccessor](minutes: MinutesContainer[A, B], bookmarkSeqNr: Long) {
  def window(start: SDateLike, end: SDateLike): MinutesState[A, B] = this.copy(minutes = minutes.window(start, end))
}

object TerminalDay {
  type TerminalDayBookmarks = Map[(Terminal, MillisSinceEpoch), MillisSinceEpoch]
}

abstract class TerminalDayLikeActor[VAL <: MinuteLike[VAL, INDEX], INDEX <: WithTimeAccessor](year: Int,
                                                                                              month: Int,
                                                                                              day: Int,
                                                                                              terminal: Terminal,
                                                                                              now: () => SDateLike,
                                                                                              maybePointInTime: Option[MillisSinceEpoch]) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d")

  val typeForPersistenceId: String

  var state: Map[INDEX, VAL] = Map()

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

  override def receiveCommand: Receive = {
    case container: MinutesContainer[VAL, INDEX] =>
      log.debug(s"Received MinutesContainer for persistence")
      updateAndPersistDiff(container)

    case GetState =>
      log.debug(s"Received GetState")

      sender() ! stateResponse

    case m => log.warn(s"Got unexpected message: $m")
  }

  private def stateResponse: Option[MinutesContainer[VAL, INDEX]] =
    if (state.nonEmpty) Option(MinutesContainer(state.values.toSet)) else None

  def diffFromMinutes(state: Map[INDEX, VAL], minutes: Iterable[MinuteLike[VAL, INDEX]]): Iterable[VAL] = {
    val nowMillis = now().millisSinceEpoch
    minutes
      .map(cm => state.get(cm.key) match {
        case None => Option(cm.toUpdatedMinute(nowMillis))
        case Some(existingCm) => cm.maybeUpdated(existingCm, nowMillis)
      })
      .collect { case Some(update) => update }
  }

  def updateStateFromDiff(state: Map[INDEX, VAL], diff: Iterable[MinuteLike[VAL, INDEX]]): Map[INDEX, VAL] =
    state ++ diff.collect {
      case cm if firstMinuteMillis <= cm.minute && cm.minute < lastMinuteMillis => (cm.key, cm.toUpdatedMinute(cm.lastUpdated.getOrElse(0L)))
    }

  def updateAndPersistDiff(container: MinutesContainer[VAL, INDEX]): Unit =
    diffFromMinutes(state, container.minutes) match {
      case noDifferences if noDifferences.isEmpty => sender() ! MinutesContainer.empty[VAL, INDEX]
      case differences =>
        state = updateStateFromDiff(state, differences)
        val messageToPersist = containerToMessage(differences)
        val replyToAndMessage = Option(sender(), MinutesContainer(differences))
        persistAndMaybeSnapshot(messageToPersist, replyToAndMessage)
    }

  def containerToMessage(differences: Iterable[VAL]): GeneratedMessage
}
