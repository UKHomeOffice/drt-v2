package actors.summaries

import actors.acking.AckingReceiver.Ack
import actors.{GetState, RecoveryActorLike, Sizes}
import akka.actor.Props
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, SDateLike, UtcDate}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.FlightsSummary.FlightsSummaryMessage

object FlightsCacheActor {
  def props(date: UtcDate, terminal: Terminal, now: () => SDateLike): Props = {
    Props(new FlightsCacheActor(date, terminal, now))
  }
}

class FlightsCacheActor(date: UtcDate,
                        terminal: Terminal,
                        val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = f"flights-summary-${terminal.toString.toLowerCase}-${date.year}-${date.month}%02d-${date.day}%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  var state: Option[Seq[ApiFlightWithSplits]] = None

  import FlightsSummaryMessageConversion._

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case tqm: FlightsSummaryMessage =>
      log.debug(s"Got a recovery message with ${tqm.flights.size} flights. Setting state")
      state = Option(flightsFromMessage(tqm))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case _ => log.warn(s"Got a snapshot message, but didn't expect one.")
  }

  override def stateToMessage: GeneratedMessage = {
    state match {
      case None => FlightsSummaryMessage(Seq())
      case Some(tqs) => flightsSummaryToMessage(tqs)
    }
  }

  override def receiveCommand: Receive = {
    case FlightsWithSplits(flights) if state.isEmpty =>
      log.info(s"Received FlightsWithSplits for persistence")
      state = Option(flights.values.toSeq)
      persistAndMaybeSnapshot(stateToMessage)

    case _: FlightsWithSplits if state.isDefined =>
      log.warn(s"Received FlightsWithSplits, but we already have state so will ignore")

    case GetState =>
      log.info(s"Received GetState")
      sender() ! state.map(FlightsWithSplits(_))
  }
}
