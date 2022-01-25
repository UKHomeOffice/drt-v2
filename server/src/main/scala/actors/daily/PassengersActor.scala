package actors.daily

import actors.persistent.{RecoveryActorLike, Sizes}
import akka.actor.ActorRef
import akka.persistence._
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.time.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import scalapb.GeneratedMessage
import server.protobuf.messages.PaxMessage.{OriginTerminalPaxCountsMessage, OriginTerminalPaxCountsMessages, PaxCountMessage}
import services.{PaxDeltas, SDate}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal


case class PointInTimeOriginTerminalDay(pointInTime: Long, origin: String, terminal: String, day: Long)

case class OriginAndTerminal(origin: PortCode, terminal: Terminal)

case object ClearState

case class GetAverageAdjustment(originAndTerminal: OriginAndTerminal, numberOfDays: Int)

object PassengersActor {
  def relevantPaxCounts(numDaysInAverage: Int, now: () => SDateLike)(paxCountMessages: Seq[PaxCountMessage]): Seq[PaxCountMessage] = {
    val cutoff = now().getLocalLastMidnight.addDays(-1 * numDaysInAverage).millisSinceEpoch
    paxCountMessages.filter(msg => msg.getDay >= cutoff)
  }
}

class PassengersActor(maxDaysToConsider: Int, numDaysInAverage: Int, val now: () => SDateLike) extends RecoveryActorLike {
  override val persistenceId = s"daily-pax"

  override val recoveryStartMillis: MillisSinceEpoch = now().millisSinceEpoch

  val log: Logger = LoggerFactory.getLogger(persistenceId)

  var originTerminalPaxNosState: Map[OriginAndTerminal, Map[(Long, Long), Int]] = Map()
  var portAverageDelta: Double = 1.0

  log.info(s"Using $numDaysInAverage days to calculate the average difference in pax")

  import PassengersActor._

  val filterRelevantPaxCounts: Seq[PaxCountMessage] => Seq[PaxCountMessage] = relevantPaxCounts(numDaysInAverage, now)

  override def processRecoveryMessage: PartialFunction[Any, Unit] = {
    case OriginTerminalPaxCountsMessage(Some(origin), Some(terminal), allPaxCountMessages) =>
      val relevantPaxCounts = filterRelevantPaxCounts(allPaxCountMessages)
      applyPaxCounts(origin, terminal, relevantPaxCounts)

    case _: OriginTerminalPaxCountsMessage =>
      log.warn(s"Ignoring OriginTerminalPaxCountsMessage with missing origin and/or terminal")

    case RecoveryCompleted =>
      setPortAverage(originTerminalPaxNosState)
      log.info(s"Recovery completed. Average for port: $portAverageDelta")

    case u =>
      log.info(s"Got unexpected recovery msg: $u")
  }

  override def processSnapshotMessage: PartialFunction[Any, Unit] = {
    case OriginTerminalPaxCountsMessages(messages) => messages.map { message =>
      applyPaxCounts(message.getOrigin, message.getTerminal, message.counts)
    }
  }

  override def receiveCommand: Receive = {
    case gad: GetAverageAdjustment => sendAverageDelta(gad, sender())
    case u => log.info(s"Got unexpected command: $u")
  }

  override def stateToMessage: GeneratedMessage = {
    log.warn("This function should not be called")
    OriginTerminalPaxCountsMessages(Seq())
  }

  def applyPaxCounts(originString: String, terminalString: String, relevantPaxCounts: Seq[PaxCountMessage]): Unit =
    if (relevantPaxCounts.nonEmpty) {
      log.debug(s"Got a OriginTerminalPaxCountsMessage with ${relevantPaxCounts.size} counts. Applying")
      val updatesForOriginTerminal = messagesToUpdates(relevantPaxCounts)
      val origin = PortCode(originString)
      val terminal = Terminal(terminalString)
      val originAndTerminal = OriginAndTerminal(origin, terminal)
      val updatedOriginTerminal = originTerminalPaxNosState.getOrElse(originAndTerminal, Map()) ++ updatesForOriginTerminal
      updateState(origin, terminal, updatedOriginTerminal)
    }

  private def setPortAverage(state: Map[OriginAndTerminal, Map[(Long, Long), Int]]): Unit = {
    val portAverageDeltas = state.values
      .map { originTerminalCounts =>
        val maybeDeltas = PaxDeltas.maybePctDeltas(originTerminalCounts, maxDaysToConsider, numDaysInAverage, () => SDate.now())
        PaxDeltas.maybeAveragePctDelta(maybeDeltas)
      }
      .collect { case Some(delta) => delta }
    val sum = portAverageDeltas.sum
    val count = portAverageDeltas.size
    portAverageDelta = if (count > 0) sum / count else 1d
  }

  private def updateState(origin: PortCode, terminal: Terminal, updateForState: Map[(Long, Long), Int]): Unit = {
    originTerminalPaxNosState = originTerminalPaxNosState.updated(OriginAndTerminal(origin, terminal), updateForState)
  }

  private def sendAverageDelta(gad: GetAverageAdjustment, replyTo: ActorRef): Unit = {
    val originTerminalPaxNos = originTerminalPaxNosState.getOrElse(gad.originAndTerminal, Map())
    val maybeDeltas = PaxDeltas.maybePctDeltas(originTerminalPaxNos, maxDaysToConsider, gad.numberOfDays, () => SDate.now())
    val maybeAverageDelta = PaxDeltas.maybeAveragePctDelta(maybeDeltas) match {
      case Some(average) => Option(average)
      case None => Option(portAverageDelta)
    }
    replyTo ! maybeAverageDelta
  }

  private def messagesToUpdates(updates: Seq[PaxCountMessage]): Map[(Long, Long), Int] = updates
    .collect {
      case PaxCountMessage(Some(pit), Some(day), Some(paxCount)) => ((pit, day), paxCount)
    }
    .toMap

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte
  override val maybeSnapshotInterval: Option[Int] = Option(1000)
}

