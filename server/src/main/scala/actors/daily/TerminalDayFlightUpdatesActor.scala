package actors.daily

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamInitialized}
import actors.daily.StreamingUpdatesLike.StopUpdates
import actors.{FlightMessageConversion, PortStateMessageConversion, StreamingJournalLike}
import akka.actor.PoisonPill
import akka.persistence.query.{EventEnvelope, PersistenceQuery}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import akka.stream.scaladsl.{Keep, Sink}
import akka.stream.{ActorMaterializer, KillSwitches, UniqueKillSwitch}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, MilliTimes, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, FlightsWithSplitsDiffMessage, FlightsWithSplitsMessage}
import services.SDate


class TerminalDayFlightUpdatesActor(
                                     year: Int,
                                     month: Int,
                                     day: Int,
                                     terminal: Terminal,
                                     val now: () => SDateLike,
                                     val journalType: StreamingJournalLike
                                   ) extends PersistentActor {


  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  var maybeKillSwitch: Option[UniqueKillSwitch] = None

  var state: FlightsWithSplits = FlightsWithSplits.empty

  val startUpdatesStream: MillisSinceEpoch => Unit = (sequenceNumber: Long) => if (maybeKillSwitch.isEmpty) {
    val (_, killSwitch) = PersistenceQuery(context.system)
      .readJournalFor[journalType.ReadJournalType](journalType.id)
      .eventsByPersistenceId(persistenceId, sequenceNumber, Long.MaxValue)
      .viaMat(KillSwitches.single)(Keep.both)
      .toMat(Sink.actorRefWithAck(self, StreamInitialized, Ack, StreamCompleted))(Keep.left)
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


  def updateState(flightsWithSplitsDiffMessage: FlightsWithSplitsDiffMessage): Unit = {
    val (updated, _) = FlightMessageConversion
      .flightWithSplitsDiffFromMessage(flightsWithSplitsDiffMessage)
      .applyTo(state, now().millisSinceEpoch)
    state = updated

    purgeOldUpdates()

  }

  def setState(message: FlightsWithSplitsMessage): Unit = {
    state = FlightsWithSplits(message.flightWithSplits.map(fwsm => {
      val fws = FlightMessageConversion.flightWithSplitsFromMessage(fwsm)
      (fws.apiFlight.unique, fws)
    }).toList)
  }

  def expireBeforeMillis: MillisSinceEpoch = now().millisSinceEpoch - MilliTimes.oneMinuteMillis

  def purgeOldUpdates(): Unit = {
    state = state.copy(flights = state.flights.collect {
      case f@(_, ApiFlightWithSplits(_, _, Some(updated))) if updated >= expireBeforeMillis => f
    })
  }

  def updatesSince(sinceMillis: MillisSinceEpoch): FlightsWithSplits = FlightsWithSplits(state.flights.filter {
    case (_, fws) => fws.lastUpdated.getOrElse(0L) >= sinceMillis
  })

  override def persistenceId: String = f"terminal-flights-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  val log: Logger = LoggerFactory.getLogger(s"$persistenceId-updates")

  override def receiveCommand: Receive = myReceiveCommand orElse streamingUpdatesReceiveCommand

  def myReceiveCommand: Receive = {
    case EventEnvelope(_, _, _, diffMessage: FlightsWithSplitsDiffMessage) =>
      updateState(diffMessage)
      sender() ! Ack
  }

  override def receiveRecover: Receive = myReceiveRecover orElse streamingUpdatesReceiveRecover

  def myReceiveRecover: Receive = {
    case SnapshotOffer(SnapshotMetadata(_, _, ts), m: FlightsWithSplitsMessage) =>
      log.debug(s"Processing snapshot offer from ${SDate(ts).toISOString()}")
      setState(m)

    case m: FlightsWithSplitsDiffMessage =>
      updateState(m)
  }

  def updatesFromMessages(minuteMessages: Seq[GeneratedMessage]): Seq[CrunchMinute] = minuteMessages.map {
    case msg: CrunchMinuteMessage => PortStateMessageConversion.crunchMinuteFromMessage(msg)
  }
}
