package actors

import actors.CrunchTerminalDaysWriteActor.{DayAndTerminals, GetTerminalDays, Tick}
import actors.acking.AckingReceiver.Ack
import akka.persistence.PersistentActor
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.CrunchState.{DayAndTerminalsMessage, TerminalDaysMessage}
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable.SortedMap

class CrunchTerminalDaysWriteActor(val now: () => SDateLike) extends PersistentActor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "crunch-terminal-days"

  override def receiveRecover: Receive = {
    case _ =>
  }

  override def receiveCommand: Receive = {
    case ctd: DayAndTerminals =>
      val replyTo = sender()
      log.info(s"Received CrunchTerminalDays $ctd")
      persist(ctd) { _ =>
        val message = DayAndTerminalsMessage(Option(ctd.day), ctd.terminals.map(_.toString))
        context.system.eventStream.publish(message)
        replyTo ! Ack
      }
  }
}

class CrunchTerminalDaysReadActor(val now: () => SDateLike) extends PersistentActor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def persistenceId: String = "crunch-terminal-days"

  var terminalDays: SortedMap[MillisSinceEpoch, List[Terminal]] = SortedMap[MillisSinceEpoch, List[Terminal]]()

  override def receiveRecover: Receive = {
    case _ =>
  }

  override def receiveCommand: Receive = {
    case Tick =>
    case GetTerminalDays =>
      val replyTo = sender()
      log.info(s"Received GetTerminalDays")
      terminalDays.headOption match {
        case Some((day, terminals)) =>
          replyTo ! DayAndTerminals(day, terminals)
          terminalDays = terminalDays.drop(1)
      }
  }
}


object CrunchTerminalDaysWriteActor {
  object Tick
  object GetTerminalDays

  case class DayAndTerminals(day: MillisSinceEpoch, terminals: List[Terminal]) {
    override def toString: String = {
      s"CrunchTerminalDays(${SDate(day, Crunch.utcTimeZone).toISODateOnly}, ${terminals.map(_.toString).mkString(", ")}"
    }
  }

}
