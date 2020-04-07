package actors.summaries

import actors.acking.AckingReceiver.Ack
import actors.{RecoveryActorLike, Sizes}
import akka.actor.Props
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.TerminalQueuesSummary.{QueueSummaryMessage, QueuesSummaryMessage, StaffSummaryMessage, TerminalQueuesSummaryMessage}
import services.SDate
import services.exports.summaries.GetSummaries
import services.exports.summaries.queues._

object TerminalQueuesSummaryActor {
  def props(date: SDateLike, terminal: Terminal, now: () => SDateLike): Props = {
    Props(classOf[TerminalQueuesSummaryActor], date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)
  }
}

class TerminalQueuesSummaryActor(year: Int,
                                 month: Int,
                                 day: Int,
                                 terminal: Terminal,
                                 val now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = s"terminal-queues-summary-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  var state: Option[TerminalQueuesSummary] = None

  import TerminalQueuesSummaryMessageConversion._

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case tqm: TerminalQueuesSummaryMessage =>
      log.info(s"Got a recovery message with ${tqm.summaries.size} summaries. Setting state")
      state = Option(terminalQueuesSummaryFromMessage(tqm))
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case _ => log.warn(s"Got a snapshot message, but didn't expect one.")
  }

  override def stateToMessage: GeneratedMessage = {
    state match {
      case None => TerminalQueuesSummaryMessage(Seq(), Seq())
      case Some(tqs) => terminalQueuesSummaryToMessage(tqs)
    }
  }

  override def receiveCommand: Receive = {
    case tqs: TerminalQueuesSummary if state.isEmpty =>
      log.info(s"Received TerminalQueuesSummary for persistence")
      state = Option(tqs)
      persistAndMaybeSnapshot(stateToMessage)
      sender() ! Ack

    case _: TerminalQueuesSummary if state.isDefined =>
      log.warn(s"Received TerminalQueuesSummary, but we already have state so will ignore")
      sender() ! Ack

    case GetSummaries =>
      log.info(s"Received GetSummaries")
      sender() ! state
  }
}

object TerminalQueuesSummaryMessageConversion {
  def terminalQueuesSummaryToMessage(tqs: TerminalQueuesSummary): TerminalQueuesSummaryMessage = {
    val queuesSummaryMessages = tqs.summaries.map(queuesSummaryToMessage)
    TerminalQueuesSummaryMessage(tqs.queues.map(_.toString), queuesSummaryMessages.toSeq)
  }

  def terminalQueuesSummaryFromMessage(tqsm: TerminalQueuesSummaryMessage): TerminalQueuesSummary = {
    val queuesSummaries = tqsm.summaries.map(queuesSummaryFromMessage)
    TerminalQueuesSummary(tqsm.queues.map(Queue(_)), queuesSummaries)
  }

  def queuesSummaryToMessage(qss: QueuesSummary): QueuesSummaryMessage = {
    val queueSummaries = qss.queueSummaries.map(queueSummaryToMessage)
    val staffSummary = staffSummaryToMessage(qss.staffSummary)

    QueuesSummaryMessage(Option(qss.start.millisSinceEpoch), queueSummaries, Option(staffSummary))
  }

  def queuesSummaryFromMessage(qss: QueuesSummaryMessage): QueuesSummary = {
    val queueSummaries = qss.queues.map(queueSummaryFromMessage)
    val staffSummary = staffSummaryFromMessage(qss.staff.getOrElse(StaffSummaryMessage()))

    QueuesSummary(SDate(qss.start.getOrElse(0L)), queueSummaries, staffSummary)
  }

  def queueSummaryToMessage(qs: QueueSummaryLike): QueueSummaryMessage = QueueSummaryMessage(
    Option(qs.pax),
    Option(qs.deskRecs),
    Option(qs.waitTime),
    qs.actDesks,
    qs.actWaitTime)

  def queueSummaryFromMessage(qs: QueueSummaryMessage): QueueSummaryLike = QueueSummary(
    qs.pax.getOrElse(0),
    qs.deskRecs.getOrElse(0),
    qs.waitTime.getOrElse(0),
    qs.actDesks,
    qs.actWaitTime)

  def staffSummaryToMessage(ss: StaffSummaryLike): StaffSummaryMessage = StaffSummaryMessage(
    Option(ss.available),
    Option(ss.misc),
    Option(ss.moves),
    Option(ss.recommended))

  def staffSummaryFromMessage(ss: StaffSummaryMessage): StaffSummaryLike = StaffSummary(
    ss.available.getOrElse(0),
    ss.misc.getOrElse(0),
    ss.moves.getOrElse(0),
    ss.recommended.getOrElse(0))
}
