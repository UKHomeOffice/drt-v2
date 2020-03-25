package actors

import actors.daily.{GetAverageDelta, OriginTerminalPassengersActor}
import akka.actor.{Actor, PoisonPill, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.{MilliTimes, PortCode, SDateLike}
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class GetOriginTerminalPaxDelta(origin: PortCode, terminal: Terminal, numberOfDays: Int)

case class SetOriginTerminalDelta(origin: PortCode, terminal: Terminal, maybeDelta: Option[Int])

object PassengerDeltaActor {
  def props(now: () => SDateLike)(implicit timeout: Timeout): Props = Props(new PassengerDeltaActor(now))
}

class PassengerDeltaActor(now: () => SDateLike)(implicit val timeout: Timeout) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val cacheTimeMillis: Long = 12L * MilliTimes.oneHourMillis
  val originTerminalDeltas: mutable.Map[(PortCode, Terminal), Option[Int]] = mutable.Map()
  val originTerminalLastLookup: mutable.Map[(PortCode, Terminal), Long] = mutable.Map()

  var portAverage: Int = 0

  override def receive: Receive = {
    case SetOriginTerminalDelta(origin, terminal, maybeDelta) =>
      originTerminalDeltas += ((origin, terminal) -> maybeDelta)
      originTerminalLastLookup += ((origin, terminal) -> now().millisSinceEpoch)
      val numValues = originTerminalDeltas.values.count(_.isDefined)
      portAverage = (originTerminalDeltas.values.map(_.getOrElse(0)).sum.toDouble / numValues).round.toInt

    case GetOriginTerminalPaxDelta(origin, terminal, numberOfDays) =>
      val replyTo = sender()

      originTerminalLastLookup.get((origin, terminal)) match {
        case Some(lastMillis) if now().millisSinceEpoch - lastMillis < cacheTimeMillis =>
          originTerminalDeltas.get((origin, terminal)) match {
            case Some(Some(cachedValue)) =>
              log.debug(s"Reusing cached value $cachedValue for $origin/$terminal")
              replyTo ! cachedValue
            case _ => replyTo ! Option(portAverage)
          }
        case _ =>
          val askable: AskableActorRef = context.actorOf(OriginTerminalPassengersActor.props(origin.toString, terminal.toString))
          askable.ask(GetAverageDelta(numberOfDays)).asInstanceOf[Future[Option[Int]]]
            .map { maybeDelta =>
              context.self ! SetOriginTerminalDelta(origin, terminal, maybeDelta)
              val response = if (maybeDelta.isDefined) {
                log.info(s"Sending new value $maybeDelta for $origin/$terminal")
                maybeDelta
              } else {
                log.info(s"Sending port average value $maybeDelta for $origin/$terminal")
                Option(portAverage)
              }
              replyTo ! response
              askable.ask(PoisonPill)
            }
            .recoverWith {
              case t =>
                log.error(s"Failed to GetAverageDelta for $origin/$terminal", t)
                Future(None)
            }
      }
  }

}
