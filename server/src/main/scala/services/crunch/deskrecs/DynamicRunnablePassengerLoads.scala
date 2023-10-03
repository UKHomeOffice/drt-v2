package services.crunch.deskrecs

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute}
import drt.shared.FlightsApi.PaxForArrivals
import drt.shared._
import manifests.passengers.{ManifestLike, ManifestPaxCount}
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import queueus.DynamicQueueStatusProvider
import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Queues.{Closed, Queue, QueueStatus}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, HistoricApiFeedSource}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnablePassengerLoads {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  type HistoricManifestsProvider = Iterable[Arrival] => Source[ManifestLike, NotUsed]

  type HistoricManifestsPaxProvider = Arrival => Future[Option[ManifestPaxCount]]

  def crunchRequestsToQueueMinutes(arrivalsProvider: ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
                                   liveManifestsProvider: ProcessingRequest => Future[Source[VoyageManifests, NotUsed]],
                                   historicManifestsProvider: HistoricManifestsProvider,
                                   historicManifestsPaxProvider: HistoricManifestsPaxProvider,
                                   splitsCalculator: SplitsCalculator,
                                   splitsSink: ActorRef,
                                   portDesksAndWaitsProvider: PortDesksAndWaitsProviderLike,
                                   redListUpdatesProvider: () => Future[RedListUpdates],
                                   dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                                   queuesByTerminal: Map[Terminal, Iterable[Queue]],
                                  )
                                  (implicit
                                   ec: ExecutionContext,
                                   mat: Materializer,
                                   timeout: Timeout,
                                  ): Flow[ProcessingRequest, MinutesContainer[PassengersMinute, TQM], NotUsed] =
    Flow[ProcessingRequest]
      .wireTap(cr => log.info(s"${cr.localDate} crunch request processing started"))
      .via(addArrivals(arrivalsProvider))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing arrivals added"))
      .via(addPax(historicManifestsPaxProvider))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing pax added"))
      .via(updateHistoricApiPaxNos(splitsSink))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing pax updated"))
      .via(addSplits(liveManifestsProvider, historicManifestsProvider, splitsCalculator))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing splits added"))
      .via(updateSplits(splitsSink))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing splits persisted"))
      .via(toPassengerLoads(portDesksAndWaitsProvider, redListUpdatesProvider, dynamicQueueStatusProvider, queuesByTerminal))
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

  private def updateSplits(splitsSink: ActorRef)
                          (implicit
                           ec: ExecutionContext,
                           timeout: Timeout,
                          ): Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits]), (ProcessingRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchRequest, flights) =>
          splitsSink
            .ask(SplitsForArrivals(flights.map { fws =>
              (fws.unique, fws.splits)
            }.toMap))
            .map(_ => (crunchRequest, flights))
            .recover {
              case t =>
                log.error(s"Failed to updates splits for arrivals", t)
                (crunchRequest, flights)
            }
      }

  private def updateHistoricApiPaxNos(splitsSink: ActorRef)
                                     (implicit
                                      ec: ExecutionContext,
                                      timeout: Timeout,
                                     ): Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits]), (ProcessingRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchRequest, flights) =>
          splitsSink
            .ask(PaxForArrivals.from(flights.map(_.apiFlight), HistoricApiFeedSource))
            .map(_ => (crunchRequest, flights))
            .recover {
              case t =>
                log.error(s"Failed to update total pax for arrivals", t)
                (crunchRequest, flights)
            }
      }

  private def toPassengerLoads(portDesksAndWaitsProvider: PortDesksAndWaitsProviderLike,
                               redListUpdatesProvider: () => Future[RedListUpdates],
                               dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                               queuesByTerminal: Map[Terminal, Iterable[Queue]],
                              )
                              (implicit
                               ec: ExecutionContext,
                               mat: Materializer
                              ): Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits]), MinutesContainer[PassengersMinute, TQM], NotUsed] = {
    Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchDay, flights) =>
          log.info(s"Passenger load calculation starting: ${flights.size} flights, ${crunchDay.durationMinutes} minutes (${crunchDay.start.toISOString} to ${crunchDay.end.toISOString})")
          val eventualDeskRecs = for {
            redListUpdates <- redListUpdatesProvider()
            statuses <- dynamicQueueStatusProvider.allStatusesForPeriod(crunchDay.minutesInMillis)
            queueStatusProvider = queueStatusesProvider(statuses)
          } yield {
            val flightsPax = portDesksAndWaitsProvider.flightsToLoads(crunchDay.minutesInMillis, FlightsWithSplits(flights), redListUpdates, queueStatusProvider)
            val paxMinutesForCrunchPeriod = for {
              terminal <- queuesByTerminal.keys
              queue <- queuesByTerminal(terminal)
              minute <- crunchDay.minutesInMillis
            } yield {
              flightsPax.getOrElse(TQM(terminal, queue, minute), PassengersMinute(terminal, queue, minute, Seq(), Option(SDate.now().millisSinceEpoch)))
            }

            log.info(s"Passenger load calculation finished: (${crunchDay.start.toISOString} to ${crunchDay.end.toISOString})")
            Option(MinutesContainer(paxMinutesForCrunchPeriod.toSeq))
          }
          eventualDeskRecs.recover {
            case t =>
              log.error(s"Failed to optimise desks for ${crunchDay.localDate}", t)
              None
          }
      }
      .collect {
        case Some(minutes) => minutes
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
              log.error(s"Failed to fetch flights stream for crunch request ${crunchRequest.localDate}", t)
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
          log.info(s"DynamicRunnableDeskRecs ${crunchRequest.localDate}: addArrivals took ${SDate.now().millisSinceEpoch - startTime.millisSinceEpoch} ms")
          requestWithArrivals
      }

  def addPax(historicManifestsPaxProvider: Arrival => Future[Option[ManifestPaxCount]])
            (implicit ec: ExecutionContext, mat: Materializer): Flow[(ProcessingRequest, List[ApiFlightWithSplits]), (ProcessingRequest, List[ApiFlightWithSplits]), NotUsed] =
    Flow[(ProcessingRequest, List[ApiFlightWithSplits])]
      .mapAsync(1) { case (cr, flights) =>
        val startTime = SDate.now()
        Source(flights)
          .mapAsync(1) { flight =>
            if (flight.apiFlight.hasNoPaxSource) {
              historicManifestsPaxProvider(flight.apiFlight).map {
                case Some(manifestPaxLike: ManifestPaxCount) =>
                  val paxSources = flight.apiFlight.PassengerSources.updated(HistoricApiFeedSource, Passengers(manifestPaxLike.pax, None))
                  val updatedArrival = flight.apiFlight.copy(PassengerSources = paxSources)
                  flight.copy(apiFlight = updatedArrival)
                case None => flight
              }.recover { case e =>
                log.error(s"DynamicRunnableDeskRecs error while addArrivals ${e.getMessage}")
                flight
              }
            } else Future.successful(flight)
          }
          .runWith(Sink.seq)
          .map { updatedFlights =>
            log.info(s"DynamicRunnableDeskRecs ${cr.localDate}: addPax took ${SDate.now().millisSinceEpoch - startTime.millisSinceEpoch} ms")
            (cr, updatedFlights.toList)
          }
      }

  def addSplits(liveManifestsProvider: ProcessingRequest => Future[Source[VoyageManifests, NotUsed]],
                historicManifestsProvider: Iterable[Arrival] => Source[ManifestLike, NotUsed],
                splitsCalculator: SplitsCalculator)
               (implicit ec: ExecutionContext, mat: Materializer): Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits]), (ProcessingRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(ProcessingRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchRequest, flightsSource) =>
          val startTime = SDate.now()
          liveManifestsProvider(crunchRequest)
            .map { manifestsStream =>
              log.info(s"DynamicRunnableDeskRecs ${crunchRequest.localDate}: addSplits live took ${SDate.now().millisSinceEpoch - startTime.millisSinceEpoch} ms")
              Option((crunchRequest, flightsSource, manifestsStream))
            }
            .recover {
              case t =>
                log.error(s"Failed to fetch live manifests", t)
                None
            }
      }
      .collect {
        case Some((crunchRequest, flightsSource, manifestsSource)) => (crunchRequest, flightsSource, manifestsSource)
      }
      .flatMapConcat {
        case (crunchRequest, arrivals, manifestsSource) => manifestsSource
          .fold(VoyageManifests.empty)(_ ++ _)
          .map(manifests => (crunchRequest, arrivals, manifests))
      }
      .map { case (crunchRequest, arrivals, manifests) =>
        val manifestsByKey = arrivalKeysToManifests(manifests.manifests)
        (crunchRequest, addManifests(arrivals, manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .mapAsync(1) { case (crunchRequest, flights) =>
        val startTime = SDate.now()
        val arrivalsToLookup = flights.filter(_.bestSplits.isEmpty).map(_.apiFlight)
        historicManifestsProvider(arrivalsToLookup)
          .runWith(Sink.seq)
          .map { manifests =>
            log.info(s"DynamicRunnableDeskRecs ${crunchRequest.localDate}: addSplits historic took ${SDate.now().millisSinceEpoch - startTime.millisSinceEpoch} ms")
            (crunchRequest, flights, manifests)
          }
      }
      .map { case (crunchRequest, flights, manifests) =>
        val manifestsByKey = arrivalKeysToManifests(manifests)
        (crunchRequest, addManifests(flights, manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .map {
        case (crunchRequest, flights) =>
          val allFlightsWithSplits = flights
            .map {
              case flightWithNoSplits if flightWithNoSplits.bestSplits.isEmpty =>
                val terminalDefault = splitsCalculator.terminalDefaultSplits(flightWithNoSplits.apiFlight.Terminal)
                flightWithNoSplits.copy(splits = Set(terminalDefault))
              case flightWithSplits => flightWithSplits
            }
            .map { fws =>
              if (fws.bestSplits.exists(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages))
                fws.copy(apiFlight = fws.apiFlight.copy(FeedSources = fws.apiFlight.FeedSources + ApiFeedSource))
              else fws
            }
          (crunchRequest, allFlightsWithSplits)
      }

  private def arrivalKeysToManifests(manifests: Iterable[ManifestLike]): Map[ArrivalKey, ManifestLike] =
    manifests
      .map { manifest =>
        manifest.maybeKey.map(arrivalKey => (arrivalKey, manifest))
      }
      .collect {
        case Some((key, vm)) => (key, vm)
      }.toMap

  def addManifests(flights: Iterable[ApiFlightWithSplits],
                   manifests: Map[ArrivalKey, ManifestLike],
                   splitsForArrival: SplitsForArrival): Iterable[ApiFlightWithSplits] =
    flights
      .map { flight =>
        val maybeNewSplits = manifests
          .get(ArrivalKey(flight.apiFlight))
          .map(splitsForArrival(_, flight.apiFlight))

        val existingSplits = maybeNewSplits match {
          case Some(splits) if splits.source == ApiSplitsWithHistoricalEGateAndFTPercentages =>
            flight.splits.filter(_.source != ApiSplitsWithHistoricalEGateAndFTPercentages)
          case _ =>
            flight.splits
        }

        flight.copy(splits = existingSplits ++ maybeNewSplits)
      }
      .map { flight =>
        flight.splits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages) match {
          case None =>
            flight
          case Some(liveSplits) =>
            val arrival = flight.apiFlight.copy(
              FeedSources = flight.apiFlight.FeedSources + ApiFeedSource,
              PassengerSources = flight.apiFlight.PassengerSources.updated(ApiFeedSource, Passengers(Option(liveSplits.totalPax), Option(liveSplits.transPax)))
            )
            flight.copy(apiFlight = arrival)
        }
      }
}
