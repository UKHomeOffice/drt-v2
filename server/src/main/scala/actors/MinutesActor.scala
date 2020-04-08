package actors

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import akka.actor.{Actor, ActorRef}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.CrunchApi._
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


case class GetStateByTerminalDateRange(terminal: Terminal, start: SDateLike, end: SDateLike)

case class UpdateStateByTerminal(terminal: Terminal, updates: Any)

object Actors {
  type MinutesLookup[A, B] = (Terminal, SDateLike) => Future[Option[MinutesContainer[A, B]]]
  type MinutesUpdate[A, B] = (Terminal, SDateLike, MinutesContainer[A, B]) => Future[Boolean]
}

class MinutesActor[A, B](now: () => SDateLike,
                         terminals: Seq[Terminal],
                         lookupPrimary: MinutesLookup[A, B],
                         lookupSecondary: MinutesLookup[A, B],
                         updateMinutes: MinutesUpdate[A, B]) extends Actor {
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher

  val log: Logger = LoggerFactory.getLogger(getClass)

  def isHistoric(date: SDateLike): Boolean = MilliTimes.isHistoric(now, date)

  val minutesBuffer: mutable.Map[B, MinuteLike[A, B]] = mutable.Map[B, MinuteLike[A, B]]()
  var maybeUpdateSubscriber: Option[AskableActorRef] = None
  var subscriberIsReady: Boolean = true

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case SetSimulationActor(subscriber) =>
      log.info(s"Received subscriber actor")
      maybeUpdateSubscriber = Option(subscriber)

    case SetSimulationSourceReady =>
      subscriberIsReady = true
      context.self ! HandleSimulationRequest

    case HandleSimulationRequest =>
      handleSubscriberRequest()

    case GetPortState(startMillis, endMillis) =>
      val replyTo = sender()
      val eventualMinutes = terminals.map {
        handleLookups(_, SDate(startMillis), SDate(endMillis))
      }
      combineAndSendOptionalResult(eventualMinutes, replyTo)

    case GetStateByTerminalDateRange(terminal, start, end) =>
      val replyTo = sender()
      handleLookups(terminal, start, end).onComplete {
        case Success(container) => replyTo ! container
        case Failure(t) =>
          log.error("Failed to get minutes", t)
          replyTo ! MinutesContainer(Seq())
      }

    case GetUpdatesSince(sinceMillis, startMillis, endMillis) =>
      val replyTo = sender()
      val eventualMinutes = terminals.map {
        handleLookups(_, SDate(startMillis), SDate(endMillis))
      }
      combineEventualContainers(eventualMinutes).onComplete {
        case Success(container) => replyTo ! container.updatedSince(sinceMillis)
        case Failure(t) =>
          log.error("Failed to get minutes", t)
          replyTo ! MinutesContainer(Seq())
      }

    case container: MinutesContainer[A, B] =>
      val replyTo = sender()
      val eventualUpdatesDiff = updateByTerminalDayAndGetDiff(container)
      if (maybeUpdateSubscriber.isDefined) {
        eventualUpdatesDiff.collect {
          case Some(diffMinutesContainer) =>
            minutesBuffer ++= diffMinutesContainer.minutes.map(m => (m.key, m))
            handleSubscriberRequest()
        }
      }
      eventualUpdatesDiff.onComplete(_ => replyTo ! Ack)

    case u => log.warn(s"Got an unexpected message: $u")
  }

  private def handleSubscriberRequest(): Unit = (maybeUpdateSubscriber, minutesBuffer.nonEmpty, subscriberIsReady) match {
    case (Some(simActor), true, true) =>
      subscriberIsReady = false
      simActor
        .ask(MinutesContainer(minutesBuffer.values.toList))(new Timeout(10 minutes))
        .recover {
          case t => log.error("Error sending loads to simulate", t)
        }
        .onComplete { _ =>
          context.self ! SetSimulationSourceReady
        }
      minutesBuffer.clear()
    case _ =>
  }

  def handleLookups(terminal: Terminal,
                    start: SDateLike,
                    end: SDateLike): Future[MinutesContainer[A, B]] = {
    val eventualOptions = daysToFetch(start, end).map {
      case day if isHistoric(day) =>
        log.info(s"${day.toISOString()} is historic. Looking up historic data from CrunchStateReadActor")
        handleLookup(lookupPrimary(terminal, day), Option(() => lookupSecondary(terminal, day)))
      case day =>
        log.debug(s"${day.toISOString()} is live. Look up live data from TerminalDayQueuesActor")
        handleLookup(lookupPrimary(terminal, day), None)
    }

    Future.sequence(eventualOptions).map {
      _.collect { case Some(minutes) => minutes }
        .foldLeft(MinutesContainer[A, B](Seq())) {
          case (soFar, next) => MinutesContainer(soFar.minutes ++ next.minutes)
        }
    }
  }

  def handleLookup(eventualMaybeResult: Future[Option[MinutesContainer[A, B]]],
                   maybeFallback: Option[() => Future[Option[MinutesContainer[A, B]]]]): Future[Option[MinutesContainer[A, B]]] = {
    val future = eventualMaybeResult.flatMap {
      case Some(minutes) =>
        log.debug(s"Got some minutes. Sending them")
        Future(Option(minutes))
      case None =>
        maybeFallback match {
          case None =>
            log.info(s"Got no minutes. Sending None")
            Future(None)
          case Some(fallback) =>
            log.info(s"Got no minutes. Querying the fallback")
            handleLookup(fallback(), None)
        }
    }
    future.recover { case t =>
      log.error("Failed to get a response from primary lookup source", t)
      None
    }
    future
  }

  def updateByTerminalDayAndGetDiff(container: MinutesContainer[A, B]): Future[Option[MinutesContainer[A, B]]] = {
    val eventualUpdatedMinutesDiff = container.minutes
      .groupBy(simMin => (simMin.terminal, SDate(simMin.minute).getLocalLastMidnight))
      .map {
        case ((terminal, day), terminalDayMinutes) => handleUpdateAndGetDiff(terminal, day, terminalDayMinutes)
      }
    combineEventualContainers(eventualUpdatedMinutesDiff).map(Option(_))
  }

  private def combineAndSendOptionalResult(eventualUpdatedMinutesDiff: Iterable[Future[MinutesContainer[A, B]]],
                                           replyTo: ActorRef): Unit =
    combineEventualContainers(eventualUpdatedMinutesDiff).onComplete {
      case Success(maybeMinutes) => replyTo ! maybeMinutes
      case Failure(t) =>
        log.error("Failed to get minutes", t)
    }

  private def combineEventualContainers(eventualUpdatedMinutesDiff: Iterable[Future[MinutesContainer[A, B]]]): Future[MinutesContainer[A, B]] =
    Future.sequence(eventualUpdatedMinutesDiff).map { containers =>
      containers.foldLeft(MinutesContainer[A, B](Seq())) {
        case (soFar, next) => MinutesContainer(soFar.minutes ++ next.minutes)
      }
    }

  def handleUpdateAndGetDiff(terminal: Terminal,
                             day: SDateLike,
                             minutesForDay: Iterable[MinuteLike[A, B]]): Future[MinutesContainer[A, B]] =
    updateMinutes(terminal, day, MinutesContainer(minutesForDay))

  private def daysToFetch(start: SDateLike, end: SDateLike): Seq[SDateLike] = {
    val localStart = SDate(start, Crunch.europeLondonTimeZone)
    val localEnd = SDate(end, Crunch.europeLondonTimeZone)

    (localStart.millisSinceEpoch to localEnd.millisSinceEpoch by MilliTimes.oneHourMillis)
      .map(SDate(_).getLocalLastMidnight)
      .distinct
      .sortBy(_.millisSinceEpoch)
  }
}
