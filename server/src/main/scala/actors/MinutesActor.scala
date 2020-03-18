package actors

import akka.actor.{Actor, ActorRef, Props}
import drt.shared.CrunchApi.{MinutesContainer, MinutesLike}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch

import scala.concurrent.{ExecutionContext, Future}

object MinutesActor {
  def props(now: () => SDateLike, primaryLookup: MinutesLookup, secondaryLookup: MinutesLookup)
           (implicit ec: ExecutionContext): Props =
    Props(new MinutesActor(now, primaryLookup, secondaryLookup, (_, _, _) => Future()))
}

case class GetStateByTerminalDateRange(terminal: Terminal, start: SDateLike, end: SDateLike)

case class UpdateStateByTerminal(terminal: Terminal, updates: Any)

class MinutesActor(now: () => SDateLike,
                   lookupPrimary: MinutesLookup,
                   lookupSecondary: MinutesLookup,
                   updateMinutes: MinutesUpdate)(implicit ec: ExecutionContext) extends Actor {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def isHistoric(date: SDateLike): Boolean = MilliTimes.isHistoric(now, date)

  override def receive: Receive = {
    case GetStateByTerminalDateRange(terminal, start, end) =>
      val replyTo = sender()

      daysToFetch(start, end).foreach {
        case day if isHistoric(day) =>
          log.info(s"${day.toISOString()} is historic. Looking up historic data from CrunchStateReadActor")
          handleLookup(replyTo, lookupSecondary(terminal, day), None)
        case day =>
          log.info(s"${day.toISOString()} is live. Look up live data from TerminalDayQueuesActor")
          handleLookup(replyTo, lookupPrimary(terminal, day), Option(() => lookupSecondary(terminal, day)))
      }

    case UpdateStateByTerminal(terminal, minutesContainer: MinutesLike) =>
      minutesContainer.minutes
        .groupBy(cm => SDate(cm.minute).getLocalLastMidnight)
        .map { case (day, minutesForDay) =>
          updateMinutes(terminal, day, MinutesContainer(minutesForDay))
        }
  }

  def handleLookup(replyTo: ActorRef,
                   eventualMaybeResult: Future[Option[MinutesContainer]],
                   maybeFallback: Option[() => Future[Option[MinutesContainer]]]): Unit = {
    eventualMaybeResult.map {
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
    }.recover { case t =>
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
