package actors

import akka.actor.{Actor, ActorRef, Props}
import drt.shared.CrunchApi.MinutesContainer
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.{ExecutionContext, Future}

object MinutesActor {
  def props(primaryLookup: MinutesLookup, secondaryLookup: MinutesLookup)
           (implicit ec: ExecutionContext): Props =
    Props(new MinutesActor(primaryLookup, secondaryLookup, (_, _, _) => Future()))
}

case class GetStateByTerminalDateRange(terminal: Terminal, start: SDateLike, end: SDateLike)

case class UpdateStateByTerminal(terminal: Terminal, updates: MinutesContainer)

class MinutesActor(lookupPrimary: MinutesLookup,
                   lookupSecondary: MinutesLookup,
                   updateMinutes: MinutesUpdate)(implicit ec: ExecutionContext) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case GetStateByTerminalDateRange(terminal, start, end) =>
      val replyTo = sender()

      daysToFetch(start, end).foreach { day =>
        log.info(s"${day.toISOString()} is live. Look up live data from TerminalDayQueuesActor")
        handleLookup(replyTo, lookupPrimary(terminal, day), Option(() => lookupSecondary(terminal, day)))
      }

    case UpdateStateByTerminal(terminal, minutesContainer) =>
      minutesContainer.minutes
        .groupBy(cm => SDate(cm.minute).getLocalLastMidnight)
        .foreach { case (day, minutesForDay) =>
          updateMinutes(terminal, day, MinutesContainer(minutesForDay))
        }
  }

  def handleLookup(replyTo: ActorRef,
                   eventualMaybeResult: Future[Option[MinutesContainer]],
                   maybeFallback: Option[() => Future[Option[MinutesContainer]]]): Unit = {
    eventualMaybeResult.foreach {
      case Some(minutes) =>
        log.info(s"Got some minutes. Sending them")
        replyTo ! Option(minutes)
      case None =>
        maybeFallback match {
          case None =>
            log.info(s"Got no minutes. Sending None")
            replyTo ! None
          case Some(fallback) =>
            log.info(s"Got no minutes. Querying the fallback")
            handleLookup(replyTo, fallback(), None)
        }
    }
    eventualMaybeResult.recover { case t =>
      log.error("Failed to get a response from primary lookup source", t)
      replyTo ! None
    }
  }

  private def daysToFetch(start: SDateLike, end: SDateLike): Seq[SDateLike] = {
    val localStart = SDate(start, Crunch.europeLondonTimeZone)
    val localEnd = SDate(end, Crunch.europeLondonTimeZone)

    (localStart.millisSinceEpoch to localEnd.millisSinceEpoch by MilliTimes.oneHourMillis)
      .map(SDate(_).getLocalLastMidnight)
      .distinct
      .sortBy(_.millisSinceEpoch)
  }
}
