package actors.daily

import akka.actor.{ActorRef, Props}
import akka.persistence._
import drt.shared.SDateLike
import org.slf4j.{Logger, LoggerFactory}
import server.protobuf.messages.PaxMessage.{PaxCountMessage, PaxCountsMessage}
import services.SDate


case class GetAverageDelta(numberOfDays: Int)

case object Ack

object OriginTerminalPassengersActor {
  def props(origin: String, terminal: String): Props = Props(new OriginTerminalPassengersActor(origin, terminal))
}

class OriginTerminalPassengersActor(origin: String, terminal: String) extends PersistentActor {
  override val persistenceId = s"daily-origin-terminal-pax-$origin-$terminal"

  val log: Logger = LoggerFactory.getLogger(persistenceId)

  var paxNosState: Map[(Long, Long), Int] = Map()

  override def receiveRecover: Receive = {
    case SnapshotOffer(md, PaxCountsMessage(countMessages)) =>
      println(s"Got SnapshotOffer from ${SDate(md.timestamp).toISOString}")
      paxNosState = messagesToUpdates(countMessages).map { case (pit, day, count) => ((pit, day), count) }.toMap

    case PaxCountsMessage(countMessages) =>
      log.info(s"Got a paxCountsMessage with ${countMessages.size} counts. Applying")
      paxNosState = applyDiffToExisting(messagesToUpdates(countMessages), paxNosState)

    case RecoveryCompleted =>
      log.info(s"Recovery completed")

    case u =>
      log.info(s"Got unexpected recovery msg: $u")
  }

  override def receiveCommand: Receive = {
    case GetAverageDelta(numberOfDays: Int) =>
      log.info(s"Received request for $numberOfDays days average delta")
      sendAverageDelta(numberOfDays, sender())

    case u =>
      log.info(s"Got unexpected command: $u")
  }

  private def sendAverageDelta(numberOfDays: Int, replyTo: ActorRef): Unit = {
    val maybeDeltas = PaxDeltas.maybeDeltas(paxNosState, numberOfDays, () => SDate.now())
    val maybeAverageDelta = PaxDeltas.maybeAverageDelta(maybeDeltas)
    log.info(s"*************** Sending $maybeAverageDelta")
    replyTo ! maybeAverageDelta
  }

  private def messagesToUpdates(updates: Seq[PaxCountMessage]): Seq[(Long, Long, Int)] = updates.collect {
    case PaxCountMessage(Some(pit), Some(day), Some(paxCount)) => (pit, day, paxCount)
  }

  def applyDiffToExisting(diff: Iterable[(Long, Long, Int)],
                          existing: Map[(Long, Long), Int]): Map[(Long, Long), Int] = diff.foldLeft(existing) {
    case (stateSoFar, (pit, day, paxCount)) => stateSoFar.updated((pit, day), paxCount)
  }
}

object PaxDeltas {
  def maybeAverageDelta(maybeDeltas: Seq[Option[Int]]): Option[Int] = {
    val total = maybeDeltas.collect { case Some(diff) => diff }.sum.toDouble
    maybeDeltas.count(_.isDefined) match {
      case 0 => None
      case daysWithNumbers => Option((total / daysWithNumbers).round.toInt)
    }
  }

  def maybeDeltas(dailyPaxNosByDay: Map[(Long, Long), Int],
                  numberOfDays: Int,
                  now: () => SDateLike): Seq[Option[Int]] = {
    val startDay = now().addDays(-1).getLocalLastMidnight

    (0 until numberOfDays).map { dayOffset =>
      val day = startDay.addDays(-1 * dayOffset)
      val dayBefore = day.addDays(-1)
      val maybeActualPax = dailyPaxNosByDay.get((day.millisSinceEpoch, day.millisSinceEpoch))
      val maybeForecastPax = dailyPaxNosByDay.get((dayBefore.millisSinceEpoch, day.millisSinceEpoch))
      for {
        actual <- maybeActualPax
        forecast <- maybeForecastPax
      } yield forecast - actual
    }
  }
}
