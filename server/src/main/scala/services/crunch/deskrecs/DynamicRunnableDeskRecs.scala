package services.crunch.deskrecs

import akka.NotUsed
import akka.stream.scaladsl.GraphDSL.Implicits
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.api.Arrival
import manifests.passengers.ManifestLike
import manifests.queues.SplitsCalculator
import manifests.queues.SplitsCalculator.SplitsForArrival
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch

import scala.collection.immutable.{Map, NumericRange}
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeskRecs {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val timeLogger: TimeLogger = TimeLogger("DeskRecs", 1000, log)

  type HistoricManifestsProvider = Iterable[Arrival] => Future[Source[ManifestLike, NotUsed]]

  type LoadsToQueueMinutes = (NumericRange[MillisSinceEpoch], Map[TQM, Crunch.LoadMinute], Map[Terminal, TerminalDeskLimitsLike]) => PortStateQueueMinutes

  type FlightsToLoads = (FlightsWithSplits, MillisSinceEpoch) => Map[TQM, Crunch.LoadMinute]

  def crunchRequestsToQueueMinutes(arrivalsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]],
                                   liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                                   historicManifestsProvider: HistoricManifestsProvider,
                                   splitsCalculator: SplitsCalculator,
                                   flightsToLoads: FlightsToLoads,
                                   loadsToQueueMinutes: LoadsToQueueMinutes,
                                   maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike])
                                  (crunchRequests: Implicits.PortOps[CrunchRequest])
                                  (implicit ec: ExecutionContext): Implicits.PortOps[PortStateQueueMinutes] = {
    val withArrivals = addArrivals(crunchRequests, arrivalsProvider)
    val withSplits = addSplits(withArrivals, liveManifestsProvider, historicManifestsProvider, splitsCalculator)
    toDeskRecs(withSplits, maxDesksProviders, flightsToLoads, loadsToQueueMinutes)
  }

  private def toDeskRecs(dayAndFlights: Implicits.PortOps[(CrunchRequest, Iterable[ApiFlightWithSplits])],
                         maxDesksProviders: Map[Terminal, TerminalDeskLimitsLike],
                         flightsToLoads: FlightsToLoads,
                         loadsToQueueMinutes: LoadsToQueueMinutes
                        ): Implicits.PortOps[PortStateQueueMinutes] = {
    dayAndFlights
      .map { case (crunchDay, flights) =>
        log.info(s"Crunching ${flights.size} flights, ${crunchDay.durationMinutes} minutes (${crunchDay.start.toISOString()} to ${crunchDay.end.toISOString()})")

        timeLogger.time({
          val loadsFromFlights: Map[TQM, Crunch.LoadMinute] = flightsToLoads(FlightsWithSplits(flights), crunchDay.start.millisSinceEpoch)
          loadsToQueueMinutes(crunchDay.minutesInMillis, loadsFromFlights, maxDesksProviders)
        })
      }
  }

  private def addArrivals(days: Implicits.PortOps[CrunchRequest],
                          flightsProvider: CrunchRequest => Future[Source[List[Arrival], NotUsed]])
                         (implicit ec: ExecutionContext): Implicits.PortOps[(CrunchRequest, List[Arrival])] =
    days
      .mapAsync(1) { crunchRequest =>
        flightsProvider(crunchRequest).map(flightsStream => (crunchRequest, flightsStream))
      }
      .flatMapConcat { case (crunchRequest, flights) =>
        flights.fold(List[Arrival]())(_ ++ _).map(flights => (crunchRequest, flights))
      }

  private def addSplits(crunchRequestWithArrivals: Implicits.PortOps[(CrunchRequest, List[Arrival])],
                        liveManifestsProvider: CrunchRequest => Future[Source[VoyageManifests, NotUsed]],
                        historicManifestsProvider: Iterable[Arrival] => Future[Source[ManifestLike, NotUsed]],
                        splitsCalculator: SplitsCalculator)
                       (implicit ec: ExecutionContext): Implicits.PortOps[(CrunchRequest, Iterable[ApiFlightWithSplits])] =
    crunchRequestWithArrivals
      .mapAsync(1) { case (crunchRequest, flightsSource) =>
        liveManifestsProvider(crunchRequest).map(manifestsStream => (crunchRequest, flightsSource, manifestsStream))
      }
      .flatMapConcat { case (crunchRequest, arrivals, manifestsSource) =>
        manifestsSource.fold(VoyageManifests.empty)(_ ++ _).map(manifests => (crunchRequest, arrivals, manifests))
      }
      .map { case (crunchRequest, arrivals, manifests) =>
        val manifestsByKey = arrivalKeysToManifests(manifests.manifests)
        (crunchRequest, addManifests(arrivals.map(ApiFlightWithSplits.fromArrival), manifestsByKey, splitsCalculator.splitsForArrival))
      }
      .mapAsync(1) { case (crunchRequest, flights) =>
        val arrivalsToLookup = flights.filter(_.splits.isEmpty).map(_.apiFlight)
        historicManifestsProvider(arrivalsToLookup).map(manifests => (crunchRequest, flights, manifests))
      }
      .flatMapConcat {
        case (crunchRequest, flights, manifestsSource) =>
          manifestsSource.fold[List[ManifestLike]](List[ManifestLike]())(_ :+ _).map(manifests => (crunchRequest, flights, manifests))
      }
      .map { case (crunchRequest, flights, manifests) =>
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

        ApiFlightWithSplits(flight.apiFlight, maybeSplits.toSet)
      }
    }
}
