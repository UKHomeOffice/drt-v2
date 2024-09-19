package actors.daily

import akka.persistence.SaveSnapshotSuccess
import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import uk.gov.homeoffice.drt.actor.RecoveryActorLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.WithTimeAccessor
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.utcTimeZone
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.collection.mutable


abstract class TerminalDayLikeActor[VAL <: MinuteLike[VAL, INDEX], INDEX <: WithTimeAccessor, M <: GeneratedMessage](year: Int,
                                                                                                                     month: Int,
                                                                                                                     day: Int,
                                                                                                                     terminal: Terminal,
                                                                                                                     now: () => SDateLike,
                                                                                                                     override val maybePointInTime: Option[MillisSinceEpoch],
                                                                                                                    ) extends RecoveryActorLike {
  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString}"
  }
  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d$loggerSuffix")

  val persistenceIdType: String

  val state: mutable.Map[INDEX, VAL] = mutable.Map[INDEX, VAL]()

  override def persistenceId: String = f"terminal-$persistenceIdType-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)

  val firstMinute: SDateLike = SDate(year, month, day, 0, 0, utcTimeZone)
  private val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  private val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  override def postRecoveryComplete(): Unit = {
    super.postRecoveryComplete()
    val lastMinuteOfDay = SDate(lastMinuteMillis)
    if (maybePointInTime.isEmpty && messagesPersistedSinceSnapshotCounter > 10 && lastMinuteOfDay.addDays(1) < now().getUtcLastMidnight) {
      log.info(f"Creating final snapshot for $terminal for historic day $year-$month%02d-$day%02d")
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

  private def diffFromMinutes(state: mutable.Map[INDEX, VAL], minutes: Iterable[MinuteLike[VAL, INDEX]]): Iterable[VAL] = {
    val nowMillis = now().millisSinceEpoch
    minutes
      .map(cm => state.get(cm.key) match {
        case None => Option(cm.toUpdatedMinute(nowMillis))
        case Some(existingCm) => cm.maybeUpdated(existingCm, nowMillis)
      })
      .collect { case Some(update) => update }
  }

  private def updateStateFromDiff(diff: Iterable[MinuteLike[VAL, INDEX]]): Unit =
    diff.foreach { cm =>
      if (firstMinuteMillis <= cm.minute && cm.minute <= lastMinuteMillis) state += (cm.key -> cm.toUpdatedMinute(cm.lastUpdated.getOrElse(0L)))
    }

  private def updateAndPersistDiff(container: MinutesContainer[VAL, INDEX]): Unit =
    diffFromMinutes(state, container.minutes) match {
      case noDifferences if noDifferences.isEmpty => sender() ! Set.empty[Long]
      case differences =>
        updateStateFromDiff(differences)
        val messageToPersist = containerToMessage(differences)
        val updateRequests = if (shouldSendEffectsToSubscriber(container))
          differences
            .map(v => SDate(v.minute).toLocalDate)
            .map(date => TerminalUpdateRequest(terminal, date))
            .toSet
        else Set.empty
        val replyToAndMessage = List((sender(), updateRequests))
        persistAndMaybeSnapshotWithAck(messageToPersist, replyToAndMessage)
    }

  def shouldSendEffectsToSubscriber(container: MinutesContainer[VAL, INDEX]): Boolean

  def containerToMessage(differences: Iterable[VAL]): GeneratedMessage

  val msgMinute: M => MillisSinceEpoch
  val msgLastUpdated: M => MillisSinceEpoch
  val valFromMessage: M => VAL

  private val withinWindow: M => Boolean = (msg: M) =>
    firstMinuteMillis <= msgMinute(msg) && msgMinute(msg) <= lastMinuteMillis

  private val isApplicable: M => Boolean = maybePointInTime match {
    case None => withinWindow
    case Some(pit) => (msg: M) => (msgLastUpdated(msg) <= pit) && withinWindow(msg)
  }

  def applyMessages(minuteMessages: Seq[M]): Unit =
    minuteMessages.foreach { msg =>
      if (isApplicable(msg)) {
        val cm = valFromMessage(msg)
        state += (cm.key -> cm)
      }
    }
}
