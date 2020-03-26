package actors.daily

import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.persistence._
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.Terminals.Terminal
import drt.shared.{Arrival, PortCode, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.PaxMessage.{OriginTerminalPaxCountsMessage, PaxCountMessage}
import services.SDate

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

case class PointInTimeOriginTerminalDay(pointInTime: Long, origin: String, terminal: String, day: Long)

case class OriginAndTerminal(origin: PortCode, terminal: Terminal)

case object ClearState

case class GetAverageDelta(originAndTerminal: OriginAndTerminal, numberOfDays: Int)

case object Ack

class PassengersActor extends PersistentActor {
  override val persistenceId = s"daily-pax"

  val log: Logger = LoggerFactory.getLogger(persistenceId)

  val portAverageNumberOfDays = 7
  var originTerminalPaxNosState: Map[OriginAndTerminal, Map[(Long, Long), Int]] = Map()
  var portAverageDelta: Double = 1.0

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
        val maybeDeltas = PaxDeltas.maybePctDeltas(originTerminalCounts, portAverageNumberOfDays, () => SDate.now())
        PaxDeltas.maybeAveragePctDelta(maybeDeltas)
      }
      .collect { case Some(delta) => delta }
    val sum = portAverageDeltas.sum
    val count = portAverageDeltas.size
    portAverageDelta = if (count > 0) sum / count else 1d
  }

  override def receiveCommand: Receive = {
    case gad: GetAverageDelta => sendAverageDelta(gad, sender())
    case u => log.info(s"Got unexpected command: $u")
  }

  private def sendAverageDelta(gad: GetAverageDelta, replyTo: ActorRef): Unit = {
    val maybeDeltas = PaxDeltas.maybePctDeltas(originTerminalPaxNosState.getOrElse(gad.originAndTerminal, Map()), gad.numberOfDays, () => SDate.now())
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

object PaxDeltas {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def maybeAveragePctDelta(maybeDeltas: Seq[Option[Double]]): Option[Double] = {
    val total = maybeDeltas.collect { case Some(diff) => diff }.sum
    maybeDeltas.count(_.isDefined) match {
      case 0 => None
      case daysWithNumbers =>
        val average = total / daysWithNumbers
        Option(average)
    }
  }

  def maybePctDeltas(dailyPaxNosByDay: Map[(Long, Long), Int],
                     numberOfDays: Int,
                     now: () => SDateLike): Seq[Option[Double]] = {
    val startDay = now().addDays(-1).getLocalLastMidnight

    (0 until numberOfDays).flatMap { dayOffset =>
      val day = startDay.addDays(-1 * dayOffset)
      val dayBefore = day.addDays(-1)
      val maybeActualPax = dailyPaxNosByDay.get((day.millisSinceEpoch, day.millisSinceEpoch))
      val maybeForecastPax = dailyPaxNosByDay.get((dayBefore.millisSinceEpoch, day.millisSinceEpoch))
      for {
        actual <- maybeActualPax
        forecast <- maybeForecastPax
      } yield {
        if (forecast != 0) Option(1d - ((forecast - actual).toDouble / forecast))
        else None
      }
    }
  }

  def applyPaxDeltas(passengerDeltaActor: AskableActorRef)
                    (arrivals: List[Arrival])
                    (implicit mat: Materializer, ec: ExecutionContext): Future[List[Arrival]] = Source(arrivals)
    .mapAsync(1) { arrival =>
      val request = GetAverageDelta(OriginAndTerminal(arrival.Origin, arrival.Terminal), 7)
      passengerDeltaActor.ask(request)(new Timeout(15 second)).asInstanceOf[Future[Option[Double]]]
        .map {
          case Some(delta) =>
            val updatedPax = arrival.ActPax.map(pax => (pax * delta).round.toInt) match {
              case Some(positiveWithDelta) if positiveWithDelta > 0 =>
                log.debug(s"Applying delta of $delta to ${arrival.flightCode} @ ${SDate(arrival.Scheduled).toISOString()} ${arrival.ActPax.getOrElse(0)} -> $positiveWithDelta")
                Option(positiveWithDelta)
              case _ =>
                log.debug(s"Applying delta of $delta to ${arrival.flightCode} @ ${SDate(arrival.Scheduled).toISOString()} ${arrival.ActPax.getOrElse(0)} -> 1")
                Option(1)
            }
            arrival.copy(ActPax = updatedPax)
          case None => arrival
          case u =>
            log.error(s"Got unexpected delta response: $u")
            arrival
        }
        .recover { case t =>
          log.error("Didn't get a passenger delta", t)
          arrival
        }
    }.runWith(Sink.seq).map(_.toList)
}
