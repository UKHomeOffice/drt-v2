package services.crunch.deskrecs

import akka.actor.ActorRef
import akka.pattern.StatusReply
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import queueus.DynamicQueueStatusProvider
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.{Closed, Queue, QueueStatus}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnablePassengerLoads extends DrtRunnableGraph {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(crunchQueueActor: ActorRef,
            crunchQueue: SortedSet[TerminalUpdateRequest],
            flightsProvider: TerminalUpdateRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
            deskRecsProvider: PortDesksAndWaitsProviderLike,
            redListUpdatesProvider: () => Future[RedListUpdates],
            queueStatusProvider: DynamicQueueStatusProvider,
            updateLivePaxView: MinutesContainer[CrunchApi.PassengersMinute, TQM] => Future[StatusReply[Done]],
            terminalSplits: Terminal => Option[Splits],
            queueLoadsSinkActor: ActorRef,
            queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
            paxFeedSourceOrder: List[FeedSource],
            updateCapacity: UtcDate => Future[Done],
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ec: ExecutionContext, mat: Materializer): ActorRef = {
    val passengerLoadsFlow: Flow[TerminalUpdateRequest, MinutesContainer[CrunchApi.PassengersMinute, TQM], NotUsed] =
      DynamicRunnablePassengerLoads.crunchRequestsToQueueMinutes(
        arrivalsProvider = flightsProvider,
        portDesksAndWaitsProvider = deskRecsProvider,
        redListUpdatesProvider = redListUpdatesProvider,
        queueStatusProvider,
        queuesByTerminal,
        updateLiveView = updateLivePaxView,
        paxFeedSourceOrder = paxFeedSourceOrder,
        terminalSplits = terminalSplits,
        updateCapacity = updateCapacity,
        setUpdatedAtForDay = setUpdatedAtForDay,
      )

    val (crunchRequestQueueActor, _: UniqueKillSwitch) =
      startQueuedRequestProcessingGraph(
        minutesProducer = passengerLoadsFlow,
        persistentQueueActor = crunchQueueActor,
        initialQueue = crunchQueue,
        sinkActor = queueLoadsSinkActor,
        graphName = "passenger-loads",
      )
    crunchRequestQueueActor
  }

  def crunchRequestsToQueueMinutes(arrivalsProvider: TerminalUpdateRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
                                   portDesksAndWaitsProvider: PortDesksAndWaitsProviderLike,
                                   redListUpdatesProvider: () => Future[RedListUpdates],
                                   dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                                   queuesByTerminal: Map[Terminal, Iterable[Queue]],
                                   updateLiveView: MinutesContainer[CrunchApi.PassengersMinute, TQM] => Future[StatusReply[Done]],
                                   paxFeedSourceOrder: List[FeedSource],
                                   terminalSplits: Terminal => Option[Splits],
                                   updateCapacity: UtcDate => Future[Done],
                                   setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
                                  )
                                  (implicit
                                   ec: ExecutionContext,
                                   mat: Materializer,
                                  ): Flow[TerminalUpdateRequest, MinutesContainer[PassengersMinute, TQM], NotUsed] =
    Flow[TerminalUpdateRequest]
      .wireTap(cr => log.info(s"$cr crunch request - started"))
      .via(addArrivals(arrivalsProvider))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1} crunch request - found ${crWithFlights._2.size} arrivals with ${crWithFlights._2.map(_.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)).sum} passengers"))
      .via(toPassengerLoads(portDesksAndWaitsProvider, redListUpdatesProvider, dynamicQueueStatusProvider, queuesByTerminal, terminalSplits))
      .wireTap { crWithPax =>
        log.info(s"${crWithPax._1} crunch request - ${crWithPax._2.minutes.size} minutes of passenger loads with ${crWithPax._2.minutes.map(_.toMinute.passengers.size).sum} passengers")
        val datesToUpdate = Set(crWithPax._1.start.toUtcDate, crWithPax._1.end.toUtcDate)
        datesToUpdate.foreach(updateCapacity)
      }
      .via(Flow[(TerminalUpdateRequest, MinutesContainer[PassengersMinute, TQM])].map {
        case (pr, paxMinutes) =>
          setUpdatedAtForDay(pr.terminal, pr.date, SDate.now().millisSinceEpoch)
          paxMinutes
      })
      .recover {
        case t =>
          log.error(s"Failed to process crunch request", t)
          MinutesContainer.empty[PassengersMinute, TQM]
      }


  def validApiPercentage(flights: Iterable[ApiFlightWithSplits]): Double = {
    val totalLiveSplits = flights.count(_.hasApi)
    val validLiveSplits = flights.count(_.hasValidApi)
    if (totalLiveSplits > 0) {
      (validLiveSplits.toDouble / totalLiveSplits) * 100
    } else 100
  }

  private def toPassengerLoads(portDesksAndWaitsProvider: PortDesksAndWaitsProviderLike,
                               redListUpdatesProvider: () => Future[RedListUpdates],
                               dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                               queuesByTerminal: Map[Terminal, Iterable[Queue]],
                               terminalSplits: Terminal => Option[Splits],
                              )
                              (implicit
                               ec: ExecutionContext,
                               mat: Materializer
                              ): Flow[(TerminalUpdateRequest, Iterable[ApiFlightWithSplits]), (TerminalUpdateRequest, MinutesContainer[PassengersMinute, TQM]), NotUsed] = {
    Flow[(TerminalUpdateRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (request, flights) =>
          log.info(s"Passenger load calculation starting: ${request.date.toISOString}, ${request.terminal}, ${flights.size} flights")
          val eventualDeskRecs = for {
            redListUpdates <- redListUpdatesProvider()
            statuses <- dynamicQueueStatusProvider.allStatusesForPeriod(request.minutesInMillis)
            queueStatusProvider = queueStatusesProvider(statuses)
          } yield {
            val flightsPax = portDesksAndWaitsProvider.flightsToLoads(request.minutesInMillis, FlightsWithSplits(flights), redListUpdates, queueStatusProvider, terminalSplits)
            val paxMinutesForCrunchPeriod = for {
              queue <- queuesByTerminal(request.terminal)
              minute <- request.minutesInMillis
            } yield {
              flightsPax.getOrElse(TQM(request.terminal, queue, minute), PassengersMinute(request.terminal, queue, minute, Seq(), Option(SDate.now().millisSinceEpoch)))
            }

            log.info(s"Passenger load calculation finished: (${request.start.toISOString} to ${request.end.toISOString})")
            Option((request, MinutesContainer(paxMinutesForCrunchPeriod.toSeq)))
          }
          eventualDeskRecs.recover {
            case t =>
              log.error(s"Failed to optimise desks for ${request.date}", t)
              None
          }
        case unexpected =>
          log.warn(s"Ignoring unexpected request type: $unexpected")
          Future.successful(None)
      }
      .collect {
        case Some((procRequest, minutes)) => (procRequest, minutes)
      }
  }

  private def queueStatusesProvider(statuses: Map[Terminal, Map[Queue, Map[MillisSinceEpoch, QueueStatus]]],
                                   ): Terminal => (Queue, MillisSinceEpoch) => QueueStatus =
    (terminal: Terminal) => (queue: Queue, time: MillisSinceEpoch) => {
      val terminalStatuses = statuses.getOrElse(terminal, {
        log.error(s"terminal $terminal not found")
        Map[Queue, Map[MillisSinceEpoch, QueueStatus]]()
      })
      val queueStatuses = terminalStatuses.getOrElse(queue, {
        log.error(s"queue $queue not found")
        Map[MillisSinceEpoch, QueueStatus]()
      })
      queueStatuses.getOrElse(time, {
        log.error(s"time $time not found in ${queueStatuses.keys.min} to ${queueStatuses.keys.max}")
        Closed
      })
    }

  private def addArrivals(flightsProvider: TerminalUpdateRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]])
                         (implicit ec: ExecutionContext): Flow[TerminalUpdateRequest, (TerminalUpdateRequest, List[ApiFlightWithSplits]), NotUsed] =
    Flow[TerminalUpdateRequest]
      .mapAsync(1) { crunchRequest =>
        val startTime = SDate.now()
        flightsProvider(crunchRequest)
          .map(flightsStream => Option((crunchRequest, flightsStream, startTime)))
          .recover {
            case t =>
              log.error(s"Failed to fetch flights stream for crunch request ${crunchRequest.date}", t)
              None
          }
      }
      .collect {
        case Some((crunchRequest, flights, startTime)) => (crunchRequest, flights, startTime)
      }
      .flatMapConcat {
        case (crunchRequest, flightsStream, startTime) =>
          val requestWithArrivals = flightsStream
            .fold(List[ApiFlightWithSplits]())(_ ++ _)
            .map(flights => (crunchRequest, flights))
          log.info(s"DynamicRunnableDeskRecs ${crunchRequest.date}: addArrivals took ${SDate.now().millisSinceEpoch - startTime.millisSinceEpoch} ms")
          requestWithArrivals
      }
}
