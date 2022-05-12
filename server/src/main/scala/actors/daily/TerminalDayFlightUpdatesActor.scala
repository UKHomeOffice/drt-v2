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
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import org.slf4j.{Logger, LoggerFactory}
import services.{SDate, StreamSupervision}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.protobuf.messages.CrunchState.{FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDateLike}

import scala.collection.immutable.{Map, Set}


class TerminalDayFlightUpdatesActor(year: Int,
                                    month: Int,
                                    day: Int,
                                    terminal: Terminal,
                                    val now: () => SDateLike,
                                    val journalType: StreamingJournalLike
                                   ) extends PersistentActor {
  implicit val mat: Materializer = Materializer.createMaterializer(context)

  var maybeKillSwitch: Option[UniqueKillSwitch] = None

  var updates: Map[UniqueArrival, ApiFlightWithSplits] = Map()
  var removals: Set[(MillisSinceEpoch, UniqueArrival)] = Set()

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
      sender() ! updatesSince(sinceMillis)

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

  def purgeOldUpdates(): Unit = {
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= expireBeforeMillis)
    removals = removals.filter {
      case (updated, _) => updated >= expireBeforeMillis
    }
  }

  def updatesSince(sinceMillis: MillisSinceEpoch): FlightsWithSplitsDiff =
    FlightsWithSplitsDiff(updates.values.filter(_.lastUpdated.getOrElse(0L) > sinceMillis), removals.collect {
      case (updated, removal) if updated > sinceMillis => removal
    })

  override def persistenceId: String = f"terminal-flights-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  override def receiveCommand: Receive = myReceiveCommand orElse streamingUpdatesReceiveCommand

  def myReceiveCommand: Receive = {
    case EventEnvelope(_, _, _, diffMessage: FlightsWithSplitsDiffMessage) =>
      updateStateFromDiffMessage(diffMessage)
      purgeOldUpdates()
      sender() ! Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), m: FlightsWithSplitsMessage) =>
      val flights = m.flightWithSplits.map(FlightMessageConversion.flightWithSplitsFromMessage)
      updates = updates ++ flights.map(f => (f.unique, f))

    case m: FlightsWithSplitsDiffMessage =>
      updateStateFromDiffMessage(m)
  }

  private def updateStateFromDiffMessage(m: FlightsWithSplitsDiffMessage): Unit = {
    val diff = FlightMessageConversion.flightWithSplitsDiffFromMessage(m)
    val incomingRemovals = diff.arrivalsToRemove
    updates = incomingRemovals.foldLeft(updates) {
      case (updatesAcc, removalKey: UniqueArrival) =>
        if (updates.contains(removalKey)) updatesAcc - removalKey else updatesAcc
      case (updatesAcc, _) =>
        log.warn(s"LegacyUniqueArrival is unsupported for streaming updates")
        updatesAcc
    } ++ diff.flightsToUpdate.map(f => (f.unique, f))
    removals = removals ++ incomingRemovals.collect {
      case ua: UniqueArrival => (m.createdAt.getOrElse(Long.MaxValue), ua)
    }
  }
}
