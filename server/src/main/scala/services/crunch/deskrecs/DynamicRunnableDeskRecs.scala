package services.crunch.deskrecs

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, SplitsForArrivals}
import drt.shared._
import manifests.passengers.ManifestLike
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import queueus.DynamicQueueStatusProvider
import services.TimeLogger
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.ApiFeedSource
import uk.gov.homeoffice.drt.ports.Queues.{Closed, Queue, QueueStatus}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val timeLogger: TimeLogger = TimeLogger("DeskRecs", 1000, log)

  type HistoricManifestsProvider = Iterable[Arrival] => Source[ManifestLike, NotUsed]

  type LoadsToQueueMinutes = (NumericRange[MillisSinceEpoch], Map[TQM, Crunch.LoadMinute], Map[Terminal, TerminalDeskLimitsLike]) => Future[PortStateQueueMinutes]

  type FlightsToLoads = (FlightsWithSplits, RedListUpdates) => Map[TQM, Crunch.LoadMinute]

  def crunchRequestsToQueueMinutes(arrivalsProvider: CrunchRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]],
                                   liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                                   historicManifestsProvider: HistoricManifestsProvider,
                                   splitsCalculator: SplitsCalculator,
                                   splitsSink: ActorRef,
                                   portDesksAndWaitsProvider: PortDesksAndWaitsProviderLike,
                                   maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
                                   redListUpdatesProvider: () => Future[RedListUpdates],
                                   dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                                  )
                                  (implicit ec: ExecutionContext, mat: Materializer, timeout: Timeout): Flow[CrunchRequest, PortStateQueueMinutes, NotUsed] =
    Flow[CrunchRequest]
      .wireTap(cr => log.info(s"${cr.localDate} crunch request processing started"))
      .via(addArrivals(arrivalsProvider))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing got flights"))
      .via(addSplits(liveManifestsProvider, historicManifestsProvider, splitsCalculator))
      .wireTap { crWithFlights =>
        log.info(s"${crWithFlights._1.localDate} crunch request processing splits added")
      }
      .via(updateSplits(splitsSink))
      .wireTap(crWithFlights => log.info(s"${crWithFlights._1.localDate} crunch request processing splits persisted"))
      .via(toDeskRecs(maxDesksProviders, portDesksAndWaitsProvider, redListUpdatesProvider, dynamicQueueStatusProvider))

  def validApiPercentage(flights: Iterable[ApiFlightWithSplits]): Double = {
    val totalLiveSplits = flights.count(_.hasApi)
    val validLiveSplits = flights.count(_.hasValidApi)
    if (totalLiveSplits > 0) {
      val percentage = (validLiveSplits.toDouble / totalLiveSplits) * 100
      log.info(s"Valid API: 100 * $validLiveSplits / $totalLiveSplits = $percentage")
      percentage
    } else 100
  }

  private def updateSplits(splitsSink: ActorRef)
                          (implicit ec: ExecutionContext, timeout: Timeout): Flow[(CrunchRequest, Iterable[ApiFlightWithSplits]), (CrunchRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(CrunchRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchRequest, flights) =>
          splitsSink
            .ask(SplitsForArrivals(flights.map(fws => (fws.unique, fws.splits)).toMap))
            .map(_ => (crunchRequest, flights))
            .recover {
              case t =>
                log.error(s"Failed to updates splits for arrivals", t)
                (crunchRequest, flights)
            }
      }

  private def toDeskRecs(maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
                         portDesksAndWaitsProvider: PortDesksAndWaitsProviderLike,
                         redListUpdatesProvider: () => Future[RedListUpdates],
                         dynamicQueueStatusProvider: DynamicQueueStatusProvider,
                        )
                        (implicit ec: ExecutionContext, mat: Materializer): Flow[(CrunchRequest, Iterable[ApiFlightWithSplits]), PortStateQueueMinutes, NotUsed] = {
    Flow[(CrunchRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchDay, flights) =>
          log.info(s"Desk optimisation starting: ${flights.size} flights, ${crunchDay.durationMinutes} minutes (${crunchDay.start.toISOString()} to ${crunchDay.end.toISOString()})")
          val eventualDeskRecs = for {
            redListUpdates <- redListUpdatesProvider()
            statuses <- dynamicQueueStatusProvider.allStatusesForPeriod(crunchDay.minutesInMillis)
            queueStatusProvider = queueStatusesProvider(statuses)
            loads = portDesksAndWaitsProvider.flightsToLoads(crunchDay.minutesInMillis, FlightsWithSplits(flights), redListUpdates, queueStatusProvider)
            deskRecs <- portDesksAndWaitsProvider.loadsToDesks(crunchDay.minutesInMillis, loads, maxDesksProviders)
          } yield {
            log.info(s"Desk optimisation finished: (${crunchDay.start.toISOString()} to ${crunchDay.end.toISOString()})")
            Option(deskRecs)
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

  private def queueStatusesProvider(statuses: Map[Terminal, Map[Queue, Map[MillisSinceEpoch, QueueStatus]]]): Terminal => (Queue, MillisSinceEpoch) => QueueStatus =
    (terminal: Terminal) => (queue: Queue, time: MillisSinceEpoch) => statuses.getOrElse(terminal, {
      log.error(s"terminal $terminal not found")
      Map[Queue, Map[MillisSinceEpoch, QueueStatus]]()
    }).getOrElse(queue, {
      log.error(s"queue $queue not found")
      Map[MillisSinceEpoch, QueueStatus]()
    }).getOrElse(time, {
      log.error(s"time $time not found")
      Closed
    })

  private def addArrivals(flightsProvider: CrunchRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]])
                         (implicit ec: ExecutionContext): Flow[CrunchRequest, (CrunchRequest, List[ApiFlightWithSplits]), NotUsed] =
    Flow[CrunchRequest]
      .mapAsync(1) { crunchRequest =>
        flightsProvider(crunchRequest)
          .map { flightsStream =>
            log.info(s"DynamicRunnableDeskRecs ${crunchRequest.localDate}: got arrivals")
            Option((crunchRequest, flightsStream))
          }
          .recover {
            case t =>
              log.error(s"Failed to fetch flights stream for crunch request ${crunchRequest.localDate}", t)
              None
          }
      }
      .collect {
        case Some((crunchRequest, flights)) => (crunchRequest, flights)
      }
      .flatMapConcat {
        case (crunchRequest, flightsStream) =>
          log.info(s"DynamicRunnableDeskRecs ${crunchRequest.localDate}: folding arrivals")
          flightsStream.fold(List[ApiFlightWithSplits]())(_ ++ _).map(flights => (crunchRequest, flights))
      }

  def addSplits(liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                historicManifestsProvider: Iterable[Arrival] => Source[ManifestLike, NotUsed],
                splitsCalculator: SplitsCalculator)
               (implicit ec: ExecutionContext, mat: Materializer): Flow[(CrunchRequest, List[ApiFlightWithSplits]), (CrunchRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(CrunchRequest, List[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (crunchRequest, flightsSource) =>
          liveManifestsProvider(crunchRequest)
            .map { manifestsStream =>
              log.info(s"DynamicRunnableDeskRecs ${crunchRequest.localDate}: got live manifests")
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
        val arrivalsToLookup = flights.filter(_.bestSplits.isEmpty).map(_.apiFlight)
        historicManifestsProvider(arrivalsToLookup)
          .runWith(Sink.seq)
          .map(manifests => (crunchRequest, flights, manifests))
      }
      .map { case (crunchRequest, flights, manifests) =>
        log.info(f"Adding historic manifests to flights")
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
          case None => flight
          case Some(liveSplits) =>
            val arrival = flight.apiFlight.copy(
              ApiPax = Option(liveSplits.totalExcludingTransferPax.toInt),
              FeedSources = flight.apiFlight.FeedSources + ApiFeedSource,
            )
            flight.copy(apiFlight = arrival)
        }
      }
}
