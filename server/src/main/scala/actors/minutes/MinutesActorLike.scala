package actors.minutes

import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}
import actors.{GetPortState, GetStateByTerminalDateRange}
import akka.actor.{Actor, ActorRef}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{MinuteLike, MinutesContainer}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike, Terminals, WithTimeAccessor}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}


object MinutesActorLike {
  type MinutesLookup[A, B <: WithTimeAccessor] = (Terminals.Terminal, SDateLike) => Future[Option[MinutesContainer[A, B]]]
  type MinutesUpdate[A, B <: WithTimeAccessor] = (Terminals.Terminal, SDateLike, MinutesContainer[A, B]) => Future[MinutesContainer[A, B]]
}

abstract class MinutesActorLike[A, B <: WithTimeAccessor](now: () => SDateLike,
                                                          terminals: Iterable[Terminal],
                                                          lookupPrimary: MinutesLookup[A, B],
                                                          lookupSecondary: MinutesLookup[A, B],
                                                          updateMinutes: MinutesUpdate[A, B]) extends Actor {
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  val log: Logger = LoggerFactory.getLogger(getClass)

  def isHistoric(date: SDateLike): Boolean = MilliTimes.isHistoric(now, date)

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case GetPortState(startMillis, endMillis) =>
      val replyTo = sender()
      val eventualMinutes = terminals.map { terminal =>
        handleLookups(terminal, SDate(startMillis), SDate(endMillis))
      }
      combineAndSendOptionalResult(eventualMinutes, replyTo)

    case GetStateByTerminalDateRange(terminal, start, end) =>
      val replyTo = sender()
      handleLookups(terminal, start, end).onComplete {
        case Success(container) => replyTo ! container
        case Failure(t) =>
          log.error("Failed to get minutes", t)
          replyTo ! MinutesContainer.empty[A, B]
      }

    case container: MinutesContainer[A, B] =>
      val replyTo = sender()
      handleUpdatesAndAck(container, replyTo)

    case u => log.warn(s"Got an unexpected message: $u")
  }

  def handleUpdatesAndAck(container: MinutesContainer[A, B],
                          replyTo: ActorRef): Future[Option[MinutesContainer[A, B]]] = {
    val eventualUpdatesDiff = updateByTerminalDayAndGetDiff(container)
    eventualUpdatesDiff.onComplete(_ => replyTo ! Ack)
    eventualUpdatesDiff
  }

  def handleLookups(terminal: Terminal,
                    start: SDateLike,
                    end: SDateLike): Future[MinutesContainer[A, B]] = {
    val eventualContainerWithBookmarks: Future[immutable.Seq[MinutesContainer[A, B]]] =
      Source(Crunch.utcDaysInPeriod(start, end).toList)
        .mapAsync(1) {
          case day if isHistoric(day) =>
            log.info(s"${day.toISOString()} is historic. Will use CrunchStateReadActor as secondary source")
            handleLookup(lookupPrimary(terminal, day), Option(() => lookupSecondary(terminal, day))).map(r => (day, r))
          case day =>
            log.info(s"${day.toISOString()} is live. Look up live data from TerminalDayQueuesActor")
            handleLookup(lookupPrimary(terminal, day), None).map(r => (day, r))
        }
        .collect {
          case (_, Some(container)) => container.window(start, end)
          case (day, None) =>
            log.error(s"Failed to get minutes for ${day.toISOString()}")
            MinutesContainer.empty[A, B]
        }
        .fold(MinutesContainer[A, B](Seq())) {
          case (soFarContainer, dayContainer) => soFarContainer ++ dayContainer
        }
        .runWith(Sink.seq)

      eventualContainerWithBookmarks.map {
        case cs if cs.nonEmpty => cs.reduce(_ ++ _)
        case _ => MinutesContainer.empty[A, B]
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
            log.debug(s"Got no minutes. Sending None")
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
    val eventualUpdatedMinutesDiff = groupByTerminalAndDay(container)
      .map {
        case ((terminal, day), terminalDayMinutes) => handleUpdateAndGetDiff(terminal, day, terminalDayMinutes)
      }
    combineEventualMinutesContainers(eventualUpdatedMinutesDiff).map(Option(_))
  }

  def groupByTerminalAndDay(container: MinutesContainer[A, B]): Map[(Terminal, SDateLike), Iterable[MinuteLike[A, B]]] =
    container.minutes
      .groupBy(simMin => (simMin.terminal, SDate(simMin.minute).getUtcLastMidnight))

  private def combineAndSendOptionalResult(eventualUpdatedMinutesDiff: Iterable[Future[MinutesContainer[A, B]]],
                                           replyTo: ActorRef): Unit =
    combineEventualMinutesContainers(eventualUpdatedMinutesDiff).onComplete {
      case Success(maybeMinutesWithBookmarks) => replyTo ! maybeMinutesWithBookmarks
      case Failure(t) => log.error("Failed to get minutes", t)
    }

  private def combineEventualMinutesContainers(eventualUpdatedMinutesDiff: Iterable[Future[MinutesContainer[A, B]]]): Future[MinutesContainer[A, B]] =
    Future
      .sequence(eventualUpdatedMinutesDiff)
      .map(_.foldLeft(MinutesContainer.empty[A, B])(_ ++ _))
      .recoverWith {
        case t =>
          log.error("Failed to combine containers", t)
          Future(MinutesContainer.empty[A, B])
      }

  def handleUpdateAndGetDiff(terminal: Terminal,
                             day: SDateLike,
                             minutesForDay: Iterable[MinuteLike[A, B]]): Future[MinutesContainer[A, B]] =
    updateMinutes(terminal, day, MinutesContainer(minutesForDay))

}
