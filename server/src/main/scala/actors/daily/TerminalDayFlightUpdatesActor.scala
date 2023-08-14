package actors.daily

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.daily.StreamingUpdatesLike.StopUpdates
import akka.actor.PoisonPill
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.{PersistentActor, RecoveryCompleted}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightUpdatesAndRemovals
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
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

  val startUpdatesStream: MillisSinceEpoch => Unit = (sequenceNumber: Long) => if (maybeKillSwitch.isEmpty) {
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .eventsByPersistenceId(persistenceId, sequenceNumber, Long.MaxValue)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))(Keep.left)
      .withAttributes(StreamSupervision.resumeStrategyWithLog(getClass.getName))
      .run()
    maybeKillSwitch = Option(killSwitch)
  }

  def streamingUpdatesReceiveCommand: Receive = {
    case StreamInitialized =>
      sender() ! Ack

    case StreamCompleted =>
      log.info("Stream completed. Shutting myself down")
      self ! PoisonPill

    case GetAllUpdatesSince(sinceMillis) =>
      val updatesToSend = updatesAndRemovals.updatesSince(sinceMillis)
      sender() ! updatesToSend

    case StopUpdates =>
      stopUpdatesStream()

    case x => log.warn(s"Received unexpected message ${x.getClass}")
  }

  private def stopUpdatesStream(): Unit = {
    maybeKillSwitch.foreach(_.shutdown())
  }

  def streamingUpdatesReceiveRecover: Receive = {
    case RecoveryCompleted =>
      log.info(s"Recovered. Starting updates stream")
      startUpdatesStream(lastSequenceNr)

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
