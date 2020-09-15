package actors.queues

import actors.PartitionedPortStateActor.{DateRangeLike, GetStateForDateRange, PointInTimeQuery, TerminalRequest}
import actors.acking.AckingReceiver.{Ack, StreamCompleted, StreamFailure, StreamInitialized}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate, ProcessNextUpdateRequest}
import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import drt.shared.{ApiFlightWithSplits, SDateLike}
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
      log.info(s"Adding ${container.flightsToUpdate} flight updates and ${container.arrivalsToRemove} removals to requests queue")
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

  def handleAllTerminalLookupsStream(startMillis: MillisSinceEpoch,
                                     endMillis: MillisSinceEpoch,
                                     maybePit: Option[MillisSinceEpoch]): Future[FlightsWithSplits] = {
    val eventualFlightsForAllTerminals = Source(terminals.toList)
      .mapAsync(1) { terminal =>
        handleLookups(terminal, SDate(startMillis), SDate(endMillis), maybePit)
      }
    combineEventualFlightsStream(eventualFlightsForAllTerminals)
  }

  def handleUpdatesAndAck(container: FlightsWithSplitsDiff,
                          replyTo: ActorRef): Future[Set[MillisSinceEpoch]] = {
    processingRequest = true
    val eventualUpdatesDiff = updateByTerminalDayAndGetDiff(container)
    eventualUpdatesDiff
      .map(updatedMillis => updatesSubscriber ! updatedMillis)
      .onComplete { _ =>
        processingRequest = false
        replyTo ! Ack
        self ! ProcessNextUpdateRequest
      }
    eventualUpdatesDiff
  }

  def handleLookups(terminal: Terminal,
                    start: SDateLike,
                    end: SDateLike,
                    maybePointInTime: Option[MillisSinceEpoch]): Future[FlightsWithSplits] = {
    val eventualContainer: Future[immutable.Seq[FlightsWithSplits]] =
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
        .fold(FlightsWithSplits.empty) {
          case (soFarContainer, dayContainer) => soFarContainer ++ dayContainer
        }
        .runWith(Sink.seq)

    eventualContainer.map {
      case cs if cs.nonEmpty => cs.reduce(_ ++ _)
      case _ => FlightsWithSplits.empty
    }
  }

  def updateByTerminalDayAndGetDiff(container: FlightsWithSplitsDiff): Future[Set[MillisSinceEpoch]] = {
    val eventualUpdatedMinutesDiff: Source[Set[MillisSinceEpoch], NotUsed] = Source(groupByTerminalAndDay(container)).mapAsync(1) {
      case ((terminal, day), updates) => handleUpdateAndGetDiff(terminal, day, updates)
    }
    combineEventualDiffsStream(eventualUpdatedMinutesDiff)
  }

  def groupByTerminalAndDay(container: FlightsWithSplitsDiff): Map[(Terminal, SDateLike), FlightsWithSplitsDiff] = {
    val updates: Map[(Terminal, SDateLike), List[ApiFlightWithSplits]] = container.flightsToUpdate
      .groupBy(flightWithSplits => (flightWithSplits.apiFlight.Terminal, SDate(flightWithSplits.apiFlight.Scheduled).getUtcLastMidnight))
    val removals: Map[(Terminal, SDateLike), List[Arrival]] = container.arrivalsToRemove
      .groupBy(arrival => (arrival.Terminal, SDate(arrival.Scheduled).getUtcLastMidnight))

    (updates.keys ++ removals.keys)
      .map { terminalDay =>
        val diff = FlightsWithSplitsDiff(updates.getOrElse(terminalDay, List()), removals.getOrElse(terminalDay, List()))
        (terminalDay, diff)
      }
      .toMap
  }

  private def combineEventualFlightsStream(eventualFlights: Source[FlightsWithSplits, NotUsed]): Future[FlightsWithSplits] = {
    eventualFlights
      .fold(FlightsWithSplits.empty)(_ ++ _)
      .runWith(Sink.seq)
      .map {
        case containers if containers.nonEmpty => containers.reduce(_ ++ _)
        case _ => FlightsWithSplits.empty
      }
      .recoverWith {
        case t =>
          log.error("Failed to combine containers", t)
          Future(FlightsWithSplits.empty)
      }
  }

  private def combineEventualDiffsStream(eventualUpdatedMinutesDiff: Source[Set[MillisSinceEpoch], NotUsed]): Future[Set[MillisSinceEpoch]] = {
    eventualUpdatedMinutesDiff
      .fold(Set[MillisSinceEpoch]())(_ ++ _)
      .runWith(Sink.seq)
      .map {
        case containers if containers.nonEmpty => containers.reduce(_ ++ _)
        case _ => Set[MillisSinceEpoch]()
      }
      .recoverWith {
        case t =>
          log.error("Failed to combine containers", t)
          Future(Set[MillisSinceEpoch]())
      }
  }

  def handleUpdateAndGetDiff(terminal: Terminal,
                             day: SDateLike,
                             flightsDiffForTerminalDay: FlightsWithSplitsDiff): Future[Set[MillisSinceEpoch]] =
    updateMinutes(terminal, day, flightsDiffForTerminalDay)
}
