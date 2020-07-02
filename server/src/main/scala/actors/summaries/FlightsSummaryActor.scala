package actors.summaries

import actors.acking.AckingReceiver.Ack
import actors.{FlightMessageConversion, RecoveryActorLike, Sizes}
import akka.actor.Props
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ApiFlightWithSplits, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.FlightsSummary.FlightsSummaryMessage
import services.SDate
import services.exports.summaries.GetSummaries
import services.exports.summaries.flights.{TerminalFlightsSummary, TerminalFlightsSummaryLike, TerminalFlightsWithActualApiSummary}
import services.graphstages.Crunch

object FlightsSummaryActor {
  def props(date: SDateLike, terminal: Terminal, pcpPaxFn: Arrival => Int, now: () => SDateLike): Props = {
    val (year, month, day) = SDate.yearMonthDayForZone(date, Crunch.europeLondonTimeZone)
    Props(new FlightsSummaryActor(year, month, day, terminal, pcpPaxFn, now))
  }
}

case object GetSummariesWithActualApi

class FlightsSummaryActor(year: Int,
                          month: Int,
                          day: Int,
                          terminal: Terminal,
                          pcpPaxFn: Arrival => Int,
                          val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = f"flights-summary-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

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
    case tqs: TerminalFlightsSummaryLike if state.isEmpty =>
      log.info(s"Received TerminalFlightsSummaryLike for persistence")
      state = Option(tqs.flights)
      persistAndMaybeSnapshot(stateToMessage)
      sender() ! Ack

    case _: TerminalFlightsSummaryLike if state.isDefined =>
      log.warn(s"Received TerminalQueuesSummary, but we already have state so will ignore")
      sender() ! Ack

    case GetSummaries =>
      log.info(s"Received GetSummaries")
      val summaries = state.map(
        TerminalFlightsSummary(
          _,
          millisToLocalIsoDateOnly,
          millisToLocalHoursAndMinutes,
          pcpPaxFn
        ))
      sender() ! summaries

    case GetSummariesWithActualApi =>
      log.info(s"Received GetSummariesWithActualApi")
      val summaries = state.map(
        TerminalFlightsWithActualApiSummary(
          _,
          millisToLocalIsoDateOnly,
          millisToLocalHoursAndMinutes,
          pcpPaxFn
        ))
      sender() ! summaries
  }

  private val millisToLocalIsoDateOnly: MillisSinceEpoch => String = SDate.millisToLocalIsoDateOnly(Crunch.europeLondonTimeZone)

  private val millisToLocalHoursAndMinutes: MillisSinceEpoch => String = SDate.millisToLocalHoursAndMinutes(Crunch.europeLondonTimeZone)
}

object FlightsSummaryMessageConversion {
  def flightsSummaryToMessage(flights: Seq[ApiFlightWithSplits]): FlightsSummaryMessage = {
    val flightsSummaryMessages = flights.map(FlightMessageConversion.flightWithSplitsToMessage)
    FlightsSummaryMessage(flightsSummaryMessages)
  }

  def flightsFromMessage(tqsm: FlightsSummaryMessage): Seq[ApiFlightWithSplits] = {
    tqsm.flights.map(FlightMessageConversion.flightWithSplitsFromMessage)
  }
}
