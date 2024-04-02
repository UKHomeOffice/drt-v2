package actors.daily

import actors.StreamingJournalLike
import actors.daily.StreamingUpdatesLike.StopUpdates
import actors.persistent.StreamingUpdatesActor
import akka.actor.PoisonPill
import akka.pattern.StatusReply.Ack
import akka.persistence.query.EventEnvelope
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.stream.{Materializer, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightUpdatesAndRemovals
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamInitialized}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, SplitsForArrivalsMessage}
import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.FlightsDiffMessage
import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion.{arrivalsDiffFromMessage, splitsForArrivalsFromMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}


class TerminalDayFlightUpdatesActor(year: Int,
                                    month: Int,
                                    day: Int,
                                    terminal: Terminal,
                                    val now: () => SDateLike,
                                    val journalType: StreamingJournalLike
                                   ) extends PersistentActor {
  implicit val mat: Materializer = Materializer.createMaterializer(context)

  var maybeKillSwitch: Option[UniqueKillSwitch] = None

  var updatesAndRemovals: FlightUpdatesAndRemovals = FlightUpdatesAndRemovals.empty

  val startUpdatesStream: MillisSinceEpoch => UniqueKillSwitch =
    StreamingUpdatesActor.startUpdatesStream(context.system, journalType, persistenceId, self)

  def streamingUpdatesReceiveCommand: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.info("Stream completed. Shutting myself down")
      self ! PoisonPill

    case GetAllUpdatesSince(sinceMillis) =>
      sender() ! updatesAndRemovals.updatesSince(sinceMillis)

    case StopUpdates =>
      stopUpdatesStream()

    case x => log.error(s"Received unexpected message ${x.getClass}")
  }

  private def stopUpdatesStream(): Unit = {
    maybeKillSwitch.foreach(_.shutdown())
  }

  def streamingUpdatesReceiveRecover: Receive = {
    case RecoveryCompleted =>
      log.info(s"Recovered. Starting updates stream from sequence number: $lastSequenceNr")
      val killSwitch = startUpdatesStream(lastSequenceNr)
      maybeKillSwitch = Option(killSwitch)

    case unexpected =>
      log.error(s"Unexpected message: ${unexpected.getClass}")
  }

  def expireBeforeMillis: MillisSinceEpoch = now().millisSinceEpoch - MilliTimes.oneMinuteMillis

  override def persistenceId: String = f"terminal-flights-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  override def receiveCommand: Receive = myReceiveCommand orElse streamingUpdatesReceiveCommand

  def myReceiveCommand: Receive = {
    case EventEnvelope(_, _, _, diffMessage: FlightsDiffMessage) =>
      applyFlightsUpdate(diffMessage)
      sender() ! Ack
    case EventEnvelope(_, _, _, diffMessage: SplitsForArrivalsMessage) =>
      applySplitsUpdate(diffMessage)
      sender() ! Ack
    case _:EventEnvelope =>
      sender() ! Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case FlightsWithSplitsDiffMessage =>
      ()
    case diffMessage: FlightsDiffMessage =>
      applyFlightsUpdate(diffMessage)

    case diffMessage: SplitsForArrivalsMessage =>
      applySplitsUpdate(diffMessage)
  }

  private def applyFlightsUpdate(diffMessage: FlightsDiffMessage): Unit = {
    val diff = arrivalsDiffFromMessage(diffMessage)

    updatesAndRemovals = updatesAndRemovals
      .add(diff, diffMessage.createdAt.getOrElse(now().millisSinceEpoch))
      .purgeOldUpdates(expireBeforeMillis)
  }

  private def applySplitsUpdate(diffMessage: SplitsForArrivalsMessage): Unit = {
    val diff = splitsForArrivalsFromMessage(diffMessage)

    updatesAndRemovals = updatesAndRemovals
      .add(diff, diffMessage.createdAt.getOrElse(now().millisSinceEpoch))
      .purgeOldUpdates(expireBeforeMillis)
  }
}
