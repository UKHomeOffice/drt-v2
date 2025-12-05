package actors.daily

import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer}
import org.apache.pekko.persistence.SaveSnapshotSuccess
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.models.MinuteLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.collection.mutable
import scala.concurrent.{ExecutionContextExecutor, Future}


abstract class TerminalDayLikeActor[VAL <: MinuteLike[VAL, INDEX], INDEX <: WithTimeAccessor, M <: GeneratedMessage, RM <: GeneratedMessage](utcDate: UtcDate,
                                                                                                                                             terminal: Terminal,
                                                                                                                                             now: () => SDateLike,
                                                                                                                                             override val maybePointInTime: Option[MillisSinceEpoch],
                                                                                                                                            ) extends RecoveryActorLike {
  implicit val ec: ExecutionContextExecutor = context.dispatcher

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString}"
  }
  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-${utcDate.year}%04d-${utcDate.month}%02d-${utcDate.day}%02d$loggerSuffix")

  val persistenceIdType: String

  val state: mutable.Map[INDEX, VAL] = mutable.Map[INDEX, VAL]()

  val onUpdate: Option[(UtcDate, Iterable[VAL]) => Future[Unit]] = None

  override def persistenceId: String = f"terminal-$persistenceIdType-${terminal.toString.toLowerCase}-${utcDate.year}-${utcDate.month}%02d-${utcDate.day}%02d"

  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)

  val firstMinute: SDateLike = SDate(utcDate)
  protected val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  protected val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  override def postRecoveryComplete(): Unit = {
    super.postRecoveryComplete()
    val lastMinuteOfDay = SDate(lastMinuteMillis)
    if (maybePointInTime.isEmpty && messagesPersistedSinceSnapshotCounter > 10 && lastMinuteOfDay.addDays(1) < now().getUtcLastMidnight) {
      log.info(f"Creating final snapshot for $terminal for historic day ${utcDate.year}-${utcDate.month}%02d-${utcDate.day}%02d")
      saveSnapshot(stateToMessage)
    }
  }

  override def receiveCommand: Receive = {
    case container: MinutesContainer[VAL, INDEX] =>
      updateAndPersistDiff(container)

    case GetState =>
      sender() ! stateResponse

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.error(s"Got unexpected message: $m")
  }

  private def stateResponse: Option[MinutesContainer[VAL, INDEX]] =
    if (state.nonEmpty) Option(MinutesContainer(state.values.toSeq)) else None

  def removalsMinutes(state: mutable.Map[INDEX, VAL]): Iterable[INDEX]

  private def diffFromMinutes(state: mutable.Map[INDEX, VAL], incoming: Iterable[MinuteLike[VAL, INDEX]]): Iterable[VAL] = {
    val nowMillis = now().millisSinceEpoch
    incoming
      .map(cm => state.get(cm.key) match {
        case None => Option(cm.toUpdatedMinute(nowMillis))
        case Some(existingCm) => cm.maybeUpdated(existingCm, nowMillis)
      })
      .collect { case Some(update) => update }
  }

  private def updateStateFromDiff(diff: Iterable[MinuteLike[VAL, INDEX]], removals: Iterable[INDEX]): Unit = {
    removals.foreach(state -= _)
    diff.foreach { cm =>
      if (firstMinuteMillis <= cm.minute && cm.minute <= lastMinuteMillis)
        state += (cm.key -> cm.toUpdatedMinute(cm.lastUpdated.getOrElse(0L)))
    }
  }

  private def updateAndPersistDiff(container: MinutesContainer[VAL, INDEX]): Unit =
    (diffFromMinutes(state, container.minutes), removalsMinutes(state)) match {
      case (noDifferences, noRemovals) if noDifferences.isEmpty && noRemovals.isEmpty => sender() ! Set.empty[Long]
      case (updates, removals) =>
        updateStateFromDiff(updates, removals)
        onUpdate.foreach(_(utcDate, state.values))
        val messageToPersist = containerToMessage(updates, removals)
        val updateRequests = if (shouldSendEffectsToSubscriber(container))
          updates
            .map(v => SDate(v.minute).toLocalDate)
            .map(date => TerminalUpdateRequest(terminal, date))
            .toSet
        else Set.empty
        val replyToAndMessage = List((sender(), updateRequests))
        persistAndMaybeSnapshotWithAck(messageToPersist, replyToAndMessage)
    }

  def shouldSendEffectsToSubscriber(container: MinutesContainer[VAL, INDEX]): Boolean

  def containerToMessage(differences: Iterable[VAL], removals: Iterable[INDEX]): GeneratedMessage

  val msgMinute: M => MillisSinceEpoch
  val msgLastUpdated: M => MillisSinceEpoch
  val valFromMessage: M => VAL
  val indexFromMessage: RM => INDEX

  private val withinWindow: M => Boolean = (msg: M) =>
    firstMinuteMillis <= msgMinute(msg) && msgMinute(msg) <= lastMinuteMillis

  private val isApplicable: M => Boolean = maybePointInTime match {
    case None => withinWindow
    case Some(pit) => (msg: M) => (msgLastUpdated(msg) <= pit) && withinWindow(msg)
  }

  def applyMessages(minuteMessages: Seq[M], removals: Iterable[RM]): Unit = {
    removals.foreach(r => state -= indexFromMessage(r))
    minuteMessages.foreach { msg =>
      if (isApplicable(msg)) {
        val cm = valFromMessage(msg)
        state += (cm.key -> cm)
      }
    }
  }
}
