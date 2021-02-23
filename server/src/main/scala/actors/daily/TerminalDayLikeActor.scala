package actors.daily

import actors.queues.QueueLikeActor.UpdatedMillis
import actors.{GetState, RecoveryActorLike, Sizes}
import akka.persistence.{Recovery, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import drt.shared.CrunchApi.{DeskRecMinute, MillisSinceEpoch, MinuteLike, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, WithTimeAccessor}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import services.SDate
import services.graphstages.Crunch


abstract class TerminalDayLikeActor[VAL <: MinuteLike[VAL, INDEX], INDEX <: WithTimeAccessor](year: Int,
                                                                                              month: Int,
                                                                                              day: Int,
                                                                                              terminal: Terminal,
                                                                                              now: () => SDateLike,
                                                                                              maybePointInTime: Option[MillisSinceEpoch]) extends RecoveryActorLike {
  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString()}"
  }
  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d$loggerSuffix")

  val typeForPersistenceId: String

  var state: Map[INDEX, VAL] = Map()

  override def persistenceId: String = f"terminal-$typeForPersistenceId-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.utcTimeZone)
  val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = maxSnapshotInterval)
  }

  override def receiveCommand: Receive = {
    case container: MinutesContainer[VAL, INDEX] =>
      log.debug(s"Received MinutesContainer for persistence")
      updateAndPersistDiff(container)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! stateResponse

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

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
      case noDifferences if noDifferences.isEmpty => sender() ! UpdatedMillis.empty
      case differences =>
        state = updateStateFromDiff(state, differences)
        val messageToPersist = containerToMessage(differences)
        val updatedMillis = if (shouldSendEffectsToSubscriber(container))
          UpdatedMillis(differences.map(_.minute))
        else UpdatedMillis.empty

        val replyToAndMessage = Option(sender(), updatedMillis)
        persistAndMaybeSnapshotWithAck(messageToPersist, replyToAndMessage)
    }

  def shouldSendEffectsToSubscriber(container: MinutesContainer[VAL, INDEX]): Boolean =
    container.contains(classOf[DeskRecMinute])

  def containerToMessage(differences: Iterable[VAL]): GeneratedMessage

  def updatesToApply(allUpdates: Iterable[(INDEX, VAL)]): Iterable[(INDEX, VAL)] =
    maybePointInTime match {
      case None => allUpdates
      case Some(pit) => allUpdates.filter {
        case (_, cm) => cm.lastUpdated.getOrElse(0L) <= pit
      }
    }
}
