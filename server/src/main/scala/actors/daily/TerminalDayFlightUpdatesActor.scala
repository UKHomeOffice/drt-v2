package actors.daily

import actors.StreamingJournalLike
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.daily.StreamingUpdatesLike.StopUpdates
import actors.serializers.FlightMessageConversion
import akka.actor.PoisonPill
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{KillSwitches, Materializer, UniqueKillSwitch}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import services.StreamSupervision
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
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
      sender() ! updatesAndRemovals.updatesSince(sinceMillis)

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
    case EventEnvelope(_, _, _, diffMessage: FlightsWithSplitsDiffMessage) =>
      val diff = FlightMessageConversion.flightWithSplitsDiffFromMessage(diffMessage)

      updatesAndRemovals = updatesAndRemovals
        .apply(diff, diffMessage.createdAt.getOrElse(Long.MaxValue))
        .purgeOldUpdates(expireBeforeMillis)

      sender() ! Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, _), m: FlightsWithSplitsMessage) =>
      val flights = m.flightWithSplits.map(FlightMessageConversion.flightWithSplitsFromMessage)
      updatesAndRemovals = updatesAndRemovals ++ flights

    case diffMessage: FlightsWithSplitsDiffMessage =>
      val diff = FlightMessageConversion.flightWithSplitsDiffFromMessage(diffMessage)
      updatesAndRemovals = updatesAndRemovals.apply(diff, diffMessage.createdAt.getOrElse(Long.MaxValue))
  }
}
