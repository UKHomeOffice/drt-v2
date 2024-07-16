package services.crunch.deskrecs

import actors.persistent.SortedActorRefSource
import akka.actor.ActorRef
import akka.pattern.StatusReply
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{Materializer, UniqueKillSwitch}
import akka.{Done, NotUsed}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import queueus.DynamicQueueStatusProvider
import uk.gov.homeoffice.drt.actor.commands.{CrunchRequest, LoadProcessingRequest, ProcessingRequest, TerminalUpdateRequest}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.{Closed, Queue, QueueStatus}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.SortedSet
import scala.collection.immutable.SortedMap
import scala.concurrent.{ExecutionContext, Future}


trait DrtRunnableGraph {
  def startQueuedRequestProcessingGraph[A](minutesProducer: Flow[ProcessingRequest, A, NotUsed],
                                           persistentQueueActor: ActorRef,
                                           initialQueue: SortedSet[ProcessingRequest],
                                           sinkActor: ActorRef,
                                           graphName: String,
                                           processingRequest: MillisSinceEpoch => ProcessingRequest,
                                          )
                                          (implicit mat: Materializer): (ActorRef, UniqueKillSwitch) = {
    val graphSource = new SortedActorRefSource(persistentQueueActor, processingRequest, initialQueue, graphName)
    QueuedRequestProcessing.createGraph(graphSource, sinkActor, minutesProducer, graphName).run()
  }

  def setUpdatedAt(terminals: Iterable[Terminal],
                   setUpdatedAtForDay: (Terminal, LocalDate, MillisSinceEpoch) => Future[Done],
                   pr: ProcessingRequest): Unit =
    pr match {
      case TerminalUpdateRequest(terminal, localDate, _, _) =>
        setUpdatedAtForDay(terminal, localDate, SDate.now().millisSinceEpoch)
      case other =>
        val localDate = LocalDate(other.date.year, other.date.month, other.date.day)
        terminals.foreach { terminal =>
          setUpdatedAtForDay(terminal, localDate, SDate.now().millisSinceEpoch)
        }
    }
}

object DynamicRunnablePassengerLoads extends DrtRunnableGraph {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def apply(crunchQueueActor: ActorRef,
            crunchQueue: SortedSet[ProcessingRequest],
            crunchRequest: MillisSinceEpoch => CrunchRequest,
            flightsProvider: ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
            deskRecsProvider: PortDesksAndWaitsProviderLike,
            redListUpdatesProvider: () => Future[RedListUpdates],
            queueStatusProvider: DynamicQueueStatusProvider,
            updateLivePaxView: MinutesContainer[CrunchApi.PassengersMinute, TQM] => Future[StatusReply[Done]],
            terminalSplits: Terminal => Option[Splits],
            queueLoadsActor: ActorRef,
            queuesByTerminal: SortedMap[Terminal, Seq[Queue]],
            paxFeedSourceOrder: List[FeedSource],
            updateCapacity: UtcDate => Future[Done],
            setUpdatedAtForDay: (Terminal, LocalDate, Long) => Future[Done],
           )
           (implicit ec: ExecutionContext, mat: Materializer): ActorRef = {
    val passengerLoadsFlow: Flow[ProcessingRequest, MinutesContainer[CrunchApi.PassengersMinute, TQM], NotUsed] =
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
        passengerLoadsFlow,
        crunchQueueActor,
        crunchQueue,
        queueLoadsActor,
        "passenger-loads",
        crunchRequest,
      )
    crunchRequestQueueActor
  }

  def crunchRequestsToQueueMinutes(arrivalsProvider: ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
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
                                  ): Flow[ProcessingRequest, MinutesContainer[PassengersMinute, TQM], NotUsed] =
    Flow[ProcessingRequest]
      .wireTap(cr => log.info(s"${cr.date} crunch request - started"))
      .via(addArrivals(arrivalsProvider))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.date} crunch request - found ${crWithFlights._2.size} arrivals with ${crWithFlights._2.map(_.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0)).sum} passengers"))
      .via(toPassengerLoads(portDesksAndWaitsProvider, redListUpdatesProvider, dynamicQueueStatusProvider, queuesByTerminal, terminalSplits))
      .wireTap { crWithPax =>
        log.info(s"${crWithPax._1} crunch request - ${crWithPax._2.minutes.size} minutes of passenger loads with ${crWithPax._2.minutes.map(_.toMinute.passengers.size).sum} passengers")
        val datesToUpdate = Set(crWithPax._1.start.toUtcDate, crWithPax._1.end.toUtcDate)
        datesToUpdate.foreach(updateCapacity)
      }
      .via(Flow[(ProcessingRequest, MinutesContainer[PassengersMinute, TQM])].map {
        case (pr, paxMinutes) =>
          setUpdatedAt(queuesByTerminal.keys, setUpdatedAtForDay, pr)
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
                              ): Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits]), (ProcessingRequest, MinutesContainer[PassengersMinute, TQM]), NotUsed] = {
    Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (procRequest: LoadProcessingRequest, flights) =>
          log.info(s"Passenger load calculation starting: ${flights.size} flights, ${procRequest.durationMinutes} minutes (${procRequest.start.millisSinceEpoch} to ${procRequest.end.millisSinceEpoch})")
          val eventualDeskRecs = for {
            redListUpdates <- redListUpdatesProvider()
            statuses <- dynamicQueueStatusProvider.allStatusesForPeriod(procRequest.minutesInMillis)
            queueStatusProvider = queueStatusesProvider(statuses)
          } yield {
            val flightsPax = portDesksAndWaitsProvider.flightsToLoads(procRequest.minutesInMillis, FlightsWithSplits(flights), redListUpdates, queueStatusProvider, terminalSplits)
            val paxMinutesForCrunchPeriod = for {
              terminal <- queuesByTerminal.keys
              queue <- queuesByTerminal(terminal)
              minute <- procRequest.minutesInMillis
            } yield {
              flightsPax.getOrElse(TQM(terminal, queue, minute), PassengersMinute(terminal, queue, minute, Seq(), Option(SDate.now().millisSinceEpoch)))
            }

            log.info(s"Passenger load calculation finished: (${procRequest.start.toISOString} to ${procRequest.end.toISOString})")
            Option((procRequest, MinutesContainer(paxMinutesForCrunchPeriod.toSeq)))
          }
          eventualDeskRecs.recover {
            case t =>
              log.error(s"Failed to optimise desks for ${procRequest.date}", t)
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

  private def addArrivals(flightsProvider: ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]])
                         (implicit ec: ExecutionContext): Flow[ProcessingRequest, (ProcessingRequest, List[ApiFlightWithSplits]), NotUsed] =
    Flow[ProcessingRequest]
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
