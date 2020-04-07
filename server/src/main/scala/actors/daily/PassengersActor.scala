package actors.daily

import akka.actor.ActorRef
import akka.persistence._
import drt.shared.PortCode
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.PaxMessage.{OriginTerminalPaxCountsMessage, PaxCountMessage}
import services.{PaxDeltas, SDate}

import scala.language.postfixOps

case class PointInTimeOriginTerminalDay(pointInTime: Long, origin: String, terminal: String, day: Long)

case class OriginAndTerminal(origin: PortCode, terminal: Terminal)

case object ClearState

case class GetAverageAdjustment(originAndTerminal: OriginAndTerminal, numberOfDays: Int)

case object Ack

class PassengersActor(maxDaysToConsider: Int, numDaysInAverage: Int) extends PersistentActor {
  override val persistenceId = s"daily-pax"

  val log: Logger = LoggerFactory.getLogger(persistenceId)

  var originTerminalPaxNosState: Map[OriginAndTerminal, Map[(Long, Long), Int]] = Map()
  var portAverageDelta: Double = 1.0

  log.info(s"Using $numDaysInAverage days to calculate the average difference in pax")

  override def receiveRecover: Receive = {
    case OriginTerminalPaxCountsMessage(Some(origin), Some(terminal), countMessages) =>
      log.debug(s"Got a OriginTerminalPaxCountsMessage with ${countMessages.size} counts. Applying")
      val updatesForOriginTerminal = messagesToUpdates(countMessages)
      val originAndTerminal = OriginAndTerminal(PortCode(origin), Terminal(terminal))
      val updatedOriginTerminal = originTerminalPaxNosState.getOrElse(originAndTerminal, Map()) ++ updatesForOriginTerminal
      originTerminalPaxNosState = originTerminalPaxNosState.updated(originAndTerminal, updatedOriginTerminal)

    case _: OriginTerminalPaxCountsMessage =>
      log.warn(s"Ignoring OriginTerminalPaxCountsMessage with missing origin and/or terminal")

    case RecoveryCompleted =>
      setPortAverage(originTerminalPaxNosState)
      log.info(s"Recovery completed. Average for port: $portAverageDelta")

    case u =>
      log.info(s"Got unexpected recovery msg: $u")
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

  override def receiveCommand: Receive = {
    case gad: GetAverageAdjustment => sendAverageDelta(gad, sender())
    case u => log.info(s"Got unexpected command: $u")
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
}

