package actors

import actors.Actors.{MinutesLookup, MinutesUpdate}
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.daily.MinutesState
import actors.daily.TerminalDay.TerminalDayBookmarks
import akka.actor.{Actor, ActorRef}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.{MinutesContainer, _}
import drt.shared.Terminals.Terminal
import drt.shared.{MilliTimes, SDateLike, TQM, WithTimeAccessor}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch
import services.graphstages.Crunch.{LoadMinute, Loads}

import scala.collection.{immutable, mutable}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}


case class GetStateByTerminalDateRange(terminal: Terminal, start: SDateLike, end: SDateLike)

case class UpdateStateByTerminal(terminal: Terminal, updates: Any)

object Actors {
  type MinutesLookup[A, B <: WithTimeAccessor] = (Terminal, SDateLike) => Future[Option[MinutesContainer[A, B]]]
  type MinutesUpdate[A, B <: WithTimeAccessor] = (Terminal, SDateLike, MinutesContainer[A, B]) => Future[MinutesContainer[A,B]]
}
class QueueMinutesActor(now: () => SDateLike,
                        terminals: Iterable[Terminal],
                        lookupPrimary: MinutesLookup[CrunchMinute, TQM],
                        lookupSecondary: MinutesLookup[CrunchMinute, TQM],
                        updateMinutes: MinutesUpdate[CrunchMinute, TQM]) extends MinutesActor(now, terminals, lookupPrimary, lookupSecondary, updateMinutes) {

  val minutesBuffer: mutable.Map[TQM, LoadMinute] = mutable.Map[TQM, LoadMinute]()
  var maybeUpdateSubscriber: Option[ActorRef] = None
  var subscriberIsReady: Boolean = false

  override def handleUpdatesAndAck(container: MinutesContainer[CrunchMinute, TQM],
                                   replyTo: ActorRef): Future[Option[MinutesContainer[CrunchMinute, TQM]]] = {
    val eventualUpdatesDiff = super.handleUpdatesAndAck(container, replyTo)
    val gotDeskRecs = container.contains(classOf[DeskRecMinute])

    if (maybeUpdateSubscriber.isDefined && gotDeskRecs) addUpdatesToBufferAndSendToSubscriber(eventualUpdatesDiff)

    eventualUpdatesDiff
  }

class MinutesActor[A, B <: WithTimeAccessor](now: () => SDateLike,
                                             lookupPrimary: MinutesLookup[A, B],
                                             lookupSecondary: MinutesLookup[A, B],
                                             updateMinutes: MinutesUpdate[A, B]) extends Actor {
  private def addUpdatesToBufferAndSendToSubscriber(eventualUpdatesDiff: Future[Option[MinutesContainer[CrunchMinute, TQM]]]): Future[Unit] = eventualUpdatesDiff.collect {
    case Some(diffMinutesContainer) =>
      val updatedLoads = diffMinutesContainer.minutes.collect { case m: CrunchMinute =>
        (m.key, LoadMinute(m))
      }
      minutesBuffer ++= updatedLoads
      sendToSubscriber()
  }

  private def sendToSubscriber(): Unit = (maybeUpdateSubscriber, minutesBuffer.nonEmpty, subscriberIsReady) match {
    case (Some(simActor), true, true) =>
      log.info(s"Sending (${minutesBuffer.size}) minutes from buffer to subscriber")
      subscriberIsReady = false
      val loads = Loads(minutesBuffer.values.toList)
      simActor
        .ask(loads)(new Timeout(10 minutes))
        .recover {
          case t => log.error("Error sending loads to simulate", t)
        }
        .onComplete { _ =>
          context.self ! SetSimulationSourceReady
        }
      minutesBuffer.clear()
    case _ =>
      log.info(s"Not sending (${minutesBuffer.size}) minutes from buffer to subscriber")
  }

  override def receive: Receive = simulationReceives orElse super.receive

  def simulationReceives: Receive = {
    case SetSimulationActor(subscriber) =>
      log.info(s"Received subscriber actor")
      maybeUpdateSubscriber = Option(subscriber)
      subscriberIsReady = true

    case SetSimulationSourceReady =>
      subscriberIsReady = true
      context.self ! HandleSimulationRequest

    case HandleSimulationRequest =>
      sendToSubscriber()

  }
}

class StaffMinutesActor(now: () => SDateLike,
                        terminals: Iterable[Terminal],
                        lookupPrimary: MinutesLookup[StaffMinute, TM],
                        lookupSecondary: MinutesLookup[StaffMinute, TM],
                        updateMinutes: MinutesUpdate[StaffMinute, TM]) extends MinutesActor(now, terminals, lookupPrimary, lookupSecondary, updateMinutes)

object MinutesWithBookmarks {
  def empty[A, B <: WithTimeAccessor]: MinutesWithBookmarks[A, B] = MinutesWithBookmarks(MinutesContainer.empty[A, B], Map())
}

case class MinutesWithBookmarks[A, B <: WithTimeAccessor](container: MinutesContainer[A, B],
                                                          terminalDayBookmarks: TerminalDayBookmarks) {
  def ++(that: MinutesWithBookmarks[A, B]): MinutesWithBookmarks[A, B] =
    MinutesWithBookmarks(container ++ that.container, terminalDayBookmarks ++ that.terminalDayBookmarks)
}

object MinutesActor {
  type MinutesLookup[A, B <: WithTimeAccessor] = (Terminals.Terminal, SDateLike) => Future[Option[MinutesState[A, B]]]
  type MinutesUpdate[A, B <: WithTimeAccessor] = (Terminals.Terminal, SDateLike, MinutesContainer[A, B]) => Future[MinutesContainer[A, B]]
}

abstract class MinutesActor[A, B <: WithTimeAccessor](now: () => SDateLike,
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
        case Success(minutesWithBookmarks) => replyTo ! minutesWithBookmarks.container
        case Failure(t) =>
          log.error("Failed to get minutes", t)
          replyTo ! MinutesContainer(Seq())
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
                    end: SDateLike): Future[MinutesWithBookmarks[A, B]] = {
    val eventualContainerWithBookmarks: Future[immutable.Seq[(MinutesContainer[A, B], TerminalDayBookmarks)]] =
      Source(daysToFetch(start, end).toList)
        .mapAsync(1) {
          case day if isHistoric(day) =>
            log.info(s"${day.toISOString()} is historic. Will use CrunchStateReadActor as secondary source")
            handleLookup(lookupPrimary(terminal, day), Option(() => lookupSecondary(terminal, day))).map(r => (day, r))
          case day =>
            log.info(s"${day.toISOString()} is live. Look up live data from TerminalDayQueuesActor")
            handleLookup(lookupPrimary(terminal, day), None).map(r => (day, r))
        }
        .collect {
          case (day, Some(state)) =>
            println(s"terminal $terminal / day: ${day.toISOString()}")
            (day, state.window(start, end))
          case (day, None) =>
            println(s"NO STATE: terminal $terminal / day: ${day.toISOString()}")
            (day, MinutesState(MinutesContainer.empty[A, B], 0L))
        }
        .fold((MinutesContainer[A, B](Seq()), Map[(Terminal, Long), Long]())) {
          case ((soFarContainer, soFarDaySeqNrs), (day, state)) =>
            val container = soFarContainer ++ state.minutes
            val daySeqNrs = soFarDaySeqNrs + ((terminal, day.millisSinceEpoch) -> state.bookmarkSeqNr)
            (container, daySeqNrs)
        }
        .runWith(Sink.seq)

    eventualContainerWithBookmarks.map { containerBookmarks =>
      val (container, bookmarks) = containerBookmarks.headOption match {
        case Some(cb) => cb
        case None => (MinutesContainer.empty[A, B], Map[(Terminal, MillisSinceEpoch), Long]())
      }
      MinutesWithBookmarks(container, bookmarks)
    }
  }

  def handleLookup(eventualMaybeResult: Future[Option[MinutesState[A, B]]],
                   maybeFallback: Option[() => Future[Option[MinutesState[A, B]]]]): Future[Option[MinutesState[A, B]]] = {
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

  private def combineAndSendOptionalResult(eventualUpdatedMinutesDiff: Iterable[Future[MinutesWithBookmarks[A, B]]],
                                           replyTo: ActorRef): Unit =
    combineEventualMinutesWithBookmarks(eventualUpdatedMinutesDiff).onComplete {
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

  private def combineEventualMinutesWithBookmarks(eventualUpdatedMinutesDiff: Iterable[Future[MinutesWithBookmarks[A, B]]]): Future[MinutesWithBookmarks[A, B]] =
    Future
      .sequence(eventualUpdatedMinutesDiff)
      .map(_.foldLeft(MinutesWithBookmarks.empty[A, B])(_ ++ _))
      .recoverWith {
        case t =>
          log.error("Failed to combine containers", t)
          Future(MinutesWithBookmarks.empty[A, B])
      }

  def handleUpdateAndGetDiff(terminal: Terminal,
                             day: SDateLike,
                             minutesForDay: Iterable[MinuteLike[A, B]]): Future[MinutesContainer[A, B]] =
    updateMinutes(terminal, day, MinutesContainer(minutesForDay))

  private def daysToFetch(start: SDateLike, end: SDateLike): Seq[SDateLike] = {
    val utcStart = SDate(start, Crunch.utcTimeZone)
    val utcEnd = SDate(end, Crunch.utcTimeZone)

    val x = (utcStart.millisSinceEpoch to utcEnd.millisSinceEpoch by MilliTimes.oneHourMillis)
      .map(SDate(_).getUtcLastMidnight)
      .distinct
      .sortBy(_.millisSinceEpoch)
      .toList
    println(s"** daysToFetch: ${x.map(_.toISOString())}")
    x
  }
}
