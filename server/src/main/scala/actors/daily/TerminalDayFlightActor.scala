package actors.daily

import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.nebo.NeboArrivalActor
import actors.persistent.staffing.GetState
import actors.serializers.FlightMessageConversion
import actors.serializers.FlightMessageConversion.{flightWithSplitsFromMessage, uniqueArrivalsFromMessages}
import actors.persistent.{RecoveryActorLike, Sizes}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.persistence.{Recovery, SaveSnapshotSuccess, SnapshotSelectionCriteria}
import akka.util.Timeout
import controllers.DrtActorSystem
import controllers.model.RedListCounts
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff, SplitsForArrivals}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import drt.shared.dates.UtcDate
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import services.SDate
import uk.gov.homeoffice.cirium.CiriumFlightStatusApp.materializer.system

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

object TerminalDayFlightActor {
  def props(terminal: Terminal, date: UtcDate, now: () => SDateLike): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, None, None))

  def propsWithRemovalsCutoff(terminal: Terminal, date: UtcDate, now: () => SDateLike, cutOff: FiniteDuration): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, None, Option(cutOff)))

  def propsPointInTime(terminal: Terminal, date: UtcDate, now: () => SDateLike, pointInTime: MillisSinceEpoch): Props =
    Props(new TerminalDayFlightActor(date.year, date.month, date.day, terminal, now, Option(pointInTime), None))
}

class TerminalDayFlightActor(
                              year: Int,
                              month: Int,
                              day: Int,
                              terminal: Terminal,
                              val now: () => SDateLike,
                              maybePointInTime: Option[MillisSinceEpoch],
                              maybeRemovalMessageCutOff: Option[FiniteDuration]
                            ) extends RecoveryActorLike {

  val loggerSuffix: String = maybePointInTime match {
    case None => ""
    case Some(pit) => f"@${SDate(pit).toISOString()}"
  }

  val firstMinuteOfDay: SDateLike = SDate(year, month, day, 0, 0)
  val lastMinuteOfDay: SDateLike = firstMinuteOfDay.addDays(1).addMinutes(-1)

  val maybeRemovalsCutoffTimestamp: Option[MillisSinceEpoch] = maybeRemovalMessageCutOff
    .map(cutoff => firstMinuteOfDay.addDays(1).addMillis(cutoff.toMillis).millisSinceEpoch)

  override val log: Logger = LoggerFactory.getLogger(f"$getClass-$terminal-$year%04d-$month%02d-$day%02d$loggerSuffix")

  val restorer = new ArrivalsRestorer[ApiFlightWithSplits]
  var state: FlightsWithSplits = FlightsWithSplits.empty

  override def persistenceId: String = f"terminal-flights-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  private val maxSnapshotInterval = 250
  override val maybeSnapshotInterval: Option[Int] = Option(maxSnapshotInterval)
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  override def recovery: Recovery = maybePointInTime match {
    case None =>
      Recovery(SnapshotSelectionCriteria(Long.MaxValue, maxTimestamp = Long.MaxValue, 0L, 0L))
    case Some(pointInTime) =>
      val criteria = SnapshotSelectionCriteria(maxTimestamp = pointInTime)
      Recovery(fromSnapshot = criteria, replayMax = maxSnapshotInterval)
  }

  override def postRecoveryComplete(): Unit = {
    state = state.copy(flights = restorer.arrivals)
    restorer.finish()
  }

  def getRedListCount(redListPassengers: RedListPassengers, now: () => SDateLike): Future[RedListCounts] = {
    val actor: ActorRef = DrtActorSystem.actorSystem.actorOf(NeboArrivalActor.props(redListPassengers, now))
    val state: Future[RedListCounts] = actor.ask(GetState)(Timeout(60 seconds)).mapTo[RedListCounts]
    state
  }

  override def receiveCommand: Receive = {
    case redListCounts: RedListCounts =>
      log.info(s"TerminalDayFlightActor RedListCounts.................................................")
      redListCounts.counts.map { redListPassengers: RedListPassengers =>
        getRedListCount(redListPassengers, now).map { redListCount =>
          val stateDiff = redListCount.diffWith(state, now().millisSinceEpoch)(system, Timeout(60 seconds))
            .forTerminal(terminal)
            .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
          updateAndPersistDiffAndAck(stateDiff)
        }
      }


    case diff: ArrivalsDiff =>
      val stateDiff = diff
        .diffWith(state, now().millisSinceEpoch)
        .forTerminal(terminal)
        .window(firstMinuteOfDay.millisSinceEpoch, lastMinuteOfDay.millisSinceEpoch)
      updateAndPersistDiffAndAck(stateDiff)

    case splits: SplitsForArrivals =>
      val diff = splits.diff(state, now().millisSinceEpoch)
      updateAndPersistDiffAndAck(diff)

    case GetState =>
      log.debug(s"Received GetState")
      sender() ! state

    case _: SaveSnapshotSuccess =>
      ackIfRequired()

    case m => log.warn(s"Got unexpected message: $m")
  }

  def updateAndPersistDiffAndAck(diff: FlightsWithSplitsDiff): Unit = {
    val (updatedState, minutesToUpdate) = diff.applyTo(state, now().millisSinceEpoch)
    state = updatedState

    val replyToAndMessage = Option((sender(), UpdatedMillis(minutesToUpdate)))
    persistAndMaybeSnapshotWithAck(FlightMessageConversion.flightWithSplitsDiffToMessage(diff), replyToAndMessage)
  }

  def isBeforeCutoff(timestamp: Long): Boolean = maybeRemovalsCutoffTimestamp match {
    case Some(removalsCutoffTimestamp) => timestamp < removalsCutoffTimestamp
    case None => true
  }

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case FlightsWithSplitsDiffMessage(Some(createdAt), removals, updates) =>
      maybePointInTime match {
        case Some(pit) if pit < createdAt =>
          log.debug(s"Ignoring diff created more recently than the recovery point in time")
        case _ =>
          if (isBeforeCutoff(createdAt))
            restorer.remove(uniqueArrivalsFromMessages(removals))
          restorer.applyUpdates(updates.map(flightWithSplitsFromMessage))
      }
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case m: FlightsWithSplitsMessage =>
      val flights = m.flightWithSplits.map(FlightMessageConversion.flightWithSplitsFromMessage)
      restorer.applyUpdates(flights)
  }

  override def stateToMessage: GeneratedMessage = FlightMessageConversion.flightsToMessage(state.flights.values)
}
