package actors.queues

import actors.PartitionedPortStateActor.{DateRangeLike, GetStateForDateRange, PointInTimeQuery, TerminalRequest}
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate, ProcessNextUpdateRequest}
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import services.SDate
import services.graphstages.Crunch

import scala.collection.immutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.language.postfixOps


abstract class FlightsLikeActor(
                                 updatesSubscriber: ActorRef,
                                 terminals: Iterable[Terminal],
                                 lookup: FlightsLookup,
                                 updateMinutes: FlightsUpdate
                               ) extends Actor with ActorLogging {

  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher
  implicit val mat: ActorMaterializer = ActorMaterializer.create(context)

  var updateRequestsQueue: List[(ActorRef, FlightsWithSplitsDiff)] = List()
  var processingRequest: Boolean = false

  override def receive: Receive = {
    case StreamInitialized => sender() ! Ack

    case StreamCompleted => log.info(s"Stream completed")

    case StreamFailure(t) => log.error(s"Stream failed", t)

    case PointInTimeQuery(pit, GetStateForDateRange(startMillis, endMillis)) =>
      handleAllTerminalLookupsStream(startMillis, endMillis, Option(pit)).pipeTo(sender())

    case PointInTimeQuery(pit, request: DateRangeLike with TerminalRequest) =>
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), Option(pit)).pipeTo(sender())

    case GetStateForDateRange(startMillis, endMillis) =>
      handleAllTerminalLookupsStream(startMillis, endMillis, None).pipeTo(sender())

    case request: DateRangeLike with TerminalRequest =>
      handleLookups(request.terminal, SDate(request.from), SDate(request.to), None).pipeTo(sender())

    case container: FlightsWithSplitsDiff =>
      log.info(s"Adding ${container.minutes.size} minutes to requests queue")
      updateRequestsQueue = (sender(), container) :: updateRequestsQueue
      self ! ProcessNextUpdateRequest

    case ProcessNextUpdateRequest =>
      if (!processingRequest) {
        updateRequestsQueue match {
          case (replyTo, container) :: tail =>
            handleUpdatesAndAck(container, replyTo)
            updateRequestsQueue = tail
          case Nil =>
            log.debug("Update requests queue is empty. Nothing to do")
        }
      }

    case u => log.warning(s"Got an unexpected message: $u")
  }

  def handleAllTerminalLookupsStream(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, maybePit: Option[MillisSinceEpoch]): Future[FlightsWithSplitsDiff] = {
    val eventualMinutesForAllTerminals = Source(terminals.toList)
      .mapAsync(1) { terminal =>
        handleLookups(terminal, SDate(startMillis), SDate(endMillis), maybePit)
      }
    combineEventualMinutesContainersStream(eventualMinutesForAllTerminals)
  }

  def handleUpdatesAndAck(container: FlightsWithSplitsDiff,
                          replyTo: ActorRef): Future[Option[FlightsWithSplitsDiff]] = {
    processingRequest = true
    val eventualUpdatesDiff = updateByTerminalDayAndGetDiff(container)
    eventualUpdatesDiff.onComplete { _ =>
      processingRequest = false
      replyTo ! Ack
      self ! ProcessNextUpdateRequest
    }
    eventualUpdatesDiff
  }

  def handleLookups(terminal: Terminal,
                    start: SDateLike,
                    end: SDateLike,
                    maybePointInTime: Option[MillisSinceEpoch]): Future[FlightsWithSplitsDiff] = {
    val eventualContainer: Future[immutable.Seq[FlightsWithSplitsDiff]] =
      Source(Crunch.utcDaysInPeriod(start, end).toList)
        .mapAsync(1) { day =>
          lookup(terminal, day, maybePointInTime).map(r => (day, r))
        }
        .collect {
          case (_, Some(container)) =>
            container.window(start.millisSinceEpoch, end.millisSinceEpoch)
          case (day, None) =>
            log.info(s"No flights found for for ${day.toISOString()}")
            FlightsWithSplits.empty
        }
        .fold(FlightsWithSplitsDiff(List(), List())) {
          case (soFarContainer, dayContainer) => soFarContainer ++ dayContainer
        }
        .runWith(Sink.seq)

    eventualContainer.map {
      case cs if cs.nonEmpty => cs.reduce(_ ++ _)
      case _ => MinutesContainer.empty[A, B]
    }
  }


  def updateByTerminalDayAndGetDiff(container: FlightsWithSplitsDiff): Future[Option[FlightsWithSplitsDiff]] = {
    val eventualUpdatedMinutesDiff = Source(groupByTerminalAndDay(container)).mapAsync(1) {
      case ((terminal, day), terminalDayMinutes) => handleUpdateAndGetDiff(terminal, day, terminalDayMinutes)
    }
    combineEventualMinutesContainersStream(eventualUpdatedMinutesDiff).map(Option(_))
  }

  def groupByTerminalAndDay(container: FlightsWithSplitsDiff): Map[(Terminal, SDateLike), Iterable[MinuteLike[A, B]]] =
    container.minutes
      .groupBy(simMin => (simMin.terminal, SDate(simMin.minute).getUtcLastMidnight))

  private def combineEventualMinutesContainersStream(eventualUpdatedMinutesDiff: Source[FlightsWithSplitsDiff, NotUsed]): Future[FlightsWithSplitsDiff] = {
    eventualUpdatedMinutesDiff
      .fold(MinutesContainer.empty[A, B])(_ ++ _)
      .runWith(Sink.seq)
      .map {
        case containers if containers.nonEmpty => containers.reduce(_ ++ _)
        case _ => MinutesContainer.empty[A, B]
      }
      .recoverWith {
        case t =>
          log.error("Failed to combine containers", t)
          Future(MinutesContainer.empty[A, B])
      }
  }

  def handleUpdateAndGetDiff(terminal: Terminal,
                             day: SDateLike,
                             minutesForDay: Iterable[MinuteLike[A, B]]): Future[FlightsWithSplitsDiff] =
    updateMinutes(terminal, day, MinutesContainer(minutesForDay))


}
