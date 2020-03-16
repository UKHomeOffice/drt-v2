package actors.daily

import actors.acking.AckingReceiver.Ack
import actors.{FlightMessageConversion, RecoveryActorLike, Sizes}
import akka.actor.Props
import akka.persistence.SnapshotOffer
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.Terminals.Terminal
import drt.shared.{ApiFlightWithSplits, SDateLike, TQM}
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.CrunchState.{CrunchMinuteMessage, CrunchMinutesMessage}
import server.protobuf.messages.FlightsSummary.FlightsSummaryMessage
import services.SDate
import services.exports.summaries.GetSummaries
import services.exports.summaries.flights.{TerminalFlightsSummary, TerminalFlightsSummaryLike, TerminalFlightsWithActualApiSummary}
import services.graphstages.Crunch

object TerminalQueuesActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props =
    Props(classOf[TerminalQueuesActor], date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)
}

case object GetSummariesWithActualApi

class TerminalQueuesActor(year: Int,
                          month: Int,
                          day: Int,
                          terminal: Terminal,
                          val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = s"terminal-queues-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  var state: Map[TQM, CrunchMinute] = Map()

  private val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
  private val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  private val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch

  import actors.PortStateMessageConversion._

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case CrunchMinutesMessage(minuteMessages) =>
      log.info(s"Got a recovery message with ${minuteMessages.size} minutes. Updating state")
      state = state ++ minuteMessagesToKeysAndMinutes(minuteMessages)
  }

  private def minuteMessagesToKeysAndMinutes(messages: Seq[CrunchMinuteMessage]): Iterable[(TQM, CrunchMinute)] = messages
    .filter { cmm =>
      val minuteMillis = cmm.minute.getOrElse(0L)
      firstMinuteMillis <= minuteMillis && minuteMillis <= lastMinuteMillis
    }
    .map { cmm =>
      val cm = crunchMinuteFromMessage(cmm)
      (cm.key, cm)
    }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case SnapshotOffer(md, CrunchMinutesMessage(minuteMessages)) =>
      state = minuteMessagesToKeysAndMinutes(minuteMessages).toMap
  }

  override def stateToMessage: GeneratedMessage = CrunchMinutesMessage(state.values.map(crunchMinuteToMessage).toSeq)

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
      val summaries = state.map(TerminalFlightsSummary(_, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes))
      sender() ! summaries

    case GetSummariesWithActualApi =>
      log.info(s"Received GetSummariesWithActualApi")
      val summaries = state.map(TerminalFlightsWithActualApiSummary(_, millisToLocalIsoDateOnly, millisToLocalHoursAndMinutes))
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
