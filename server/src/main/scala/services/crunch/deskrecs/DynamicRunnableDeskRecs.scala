package services.crunch.deskrecs

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.{Flow, Source}
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, SplitsForArrivals}
import drt.shared._
import drt.shared.api.Arrival
import manifests.passengers.ManifestLike
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.TimeLogger
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch
import services.graphstages.QueueStatusProviders.DynamicQueueStatusProvider
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val timeLogger: TimeLogger = TimeLogger("DeskRecs", 1000, log)

  type HistoricManifestsProvider = Iterable[Arrival] => Future[Source[ManifestLike, NotUsed]]

  type LoadsToQueueMinutes = (NumericRange[MillisSinceEpoch], Map[TQM, Crunch.LoadMinute], Map[Terminal, TerminalDeskLimitsLike]) => Future[PortStateQueueMinutes]

  type FlightsToLoads = (FlightsWithSplits, RedListUpdates) => Map[TQM, Crunch.LoadMinute]

  def crunchRequestsToQueueMinutes(arrivalsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]],
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
      .via(addArrivals(arrivalsProvider))
      .via(addSplits(liveManifestsProvider, historicManifestsProvider, splitsCalculator))
      .via(updateSplits(splitsSink))
      .via(toDeskRecs(maxDesksProviders, portDesksAndWaitsProvider, redListUpdatesProvider, dynamicQueueStatusProvider))

  private def updateSplits(splitsSink: ActorRef)
                          (implicit ec: ExecutionContext, timeout: Timeout): Flow[(CrunchRequest, Iterable[ApiFlightWithSplits]), (CrunchRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(CrunchRequest, Iterable[ApiFlightWithSplits])]
      .mapAsync(1) {
        case (cr, flights) =>
          splitsSink
            .ask(SplitsForArrivals(flights.map(fws => (fws.unique, fws.splits)).toMap))
            .map(_ => (cr, flights))
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
          log.info(s"Crunch starting: ${flights.size} flights, ${crunchDay.durationMinutes} minutes (${crunchDay.start.toISOString()} to ${crunchDay.end.toISOString()})")
          for {
            redListUpdates <- redListUpdatesProvider()
            loads <- portDesksAndWaitsProvider.flightsToLoads(FlightsWithSplits(flights), redListUpdates, dynamicQueueStatusProvider)
            deskRecs <- portDesksAndWaitsProvider.loadsToDesks(crunchDay.minutesInMillis, loads, maxDesksProviders, dynamicQueueStatusProvider)
          } yield {
            log.info(s"Crunch finished: (${crunchDay.start.toISOString()} to ${crunchDay.end.toISOString()})")
            deskRecs
          }
      }
  }

  private def addArrivals(flightsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]])
                         (implicit ec: ExecutionContext): Flow[CrunchRequest, (CrunchRequest, List[Arrival]), NotUsed] =
    Flow[CrunchRequest]
      .mapAsync(1) { crunchRequest =>
        flightsProvider(crunchRequest).map(flightsStream => (crunchRequest, flightsStream))
      }
      .flatMapConcat { case (crunchRequest, flights) =>
        flights.fold(List[Arrival]())(_ ++ _).map(flights => (crunchRequest, flights))
      }

  private def addSplits(liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                        historicManifestsProvider: Iterable[Arrival] => Future[Source[ManifestLike, NotUsed]],
                        splitsCalculator: SplitsCalculator)
                       (implicit ec: ExecutionContext): Flow[(CrunchRequest, List[Arrival]), (CrunchRequest, Iterable[ApiFlightWithSplits]), NotUsed] =
    Flow[(CrunchRequest, List[Arrival])]
      .mapAsync(1) { case (crunchRequest, flightsSource) =>
        liveManifestsProvider(crunchRequest).map(manifestsStream => (crunchRequest, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (crunchRequest, arrivals, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map { manifests =>
          (crunchRequest, arrivals, manifests)
        }
      }
      .map { case (crunchRequest, arrivals, manifests) =>
        val manifestsByKey = arrivalKeysToManifests(manifests.manifests)
        (crunchRequest, addManifests(arrivals.map(ApiFlightWithSplits.fromArrival), manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .mapAsync(1) { case (crunchRequest, flights) =>
        val arrivalsToLookup = flights.filter(_.splits.isEmpty).map(_.apiFlight)
        historicManifestsProvider(arrivalsToLookup).map { manifests =>
          log.info(f"Looking up ${arrivalsToLookup.size} arrivals' historic manifests")
          (crunchRequest, flights, manifests)
        }
      }
      .flatMapConcat {
        case (crunchRequest, flights, manifestsSource) =>
          manifestsSource.fold[List[ManifestLike]](List[ManifestLike]())(_ :+ _).map(manifests => (crunchRequest, flights, manifests))
      }
      .map { case (crunchRequest, flights, manifests) =>
        log.info(f"Adding historic manifests to flights")
        val manifestsByKey = arrivalKeysToManifests(manifests)
        (crunchRequest, addManifests(flights, manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .map {
        case (crunchRequest, flights) =>
          val allFlightsWithSplits = flights.map {
            case flightWithNoSplits if flightWithNoSplits.splits.isEmpty =>
              val terminalDefault = splitsCalculator.terminalDefaultSplits(flightWithNoSplits.apiFlight.Terminal)
              flightWithNoSplits.copy(splits = Set(terminalDefault))
            case flightWithSplits => flightWithSplits
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
    flights.map { flight =>
      if (flight.splits.nonEmpty) flight
      else {
        val maybeSplits = manifests
          .get(ArrivalKey(flight.apiFlight))
          .map(splitsForArrival(_, flight.apiFlight))

        val arrival = maybeSplits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages) match {
          case None => flight.apiFlight
          case Some(liveSplits) => flight.apiFlight.copy(
            ApiPax = Option(liveSplits.totalExcludingTransferPax.toInt)
          )
        }

        ApiFlightWithSplits(arrival, maybeSplits.toSet)
      }
    }
}
