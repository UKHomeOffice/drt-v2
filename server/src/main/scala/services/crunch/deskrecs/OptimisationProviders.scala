package services.crunch.deskrecs

import actors.PartitionedPortStateActor.{GetFlightsForTerminalDateRange, GetStateForTerminalDateRange}
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute, StaffMinute}
import drt.shared.{TM, TQM}
import manifests.ManifestLookupLike
import manifests.passengers.{ManifestLike, ManifestPaxCount}
import org.slf4j.{Logger, LoggerFactory}
import services.metrics.Metrics
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object OptimisationProviders {
  private val log: Logger = LoggerFactory.getLogger(getClass)

  def historicManifestsProvider(destination: PortCode,
                                manifestLookupService: ManifestLookupLike,
                                cacheLookup: Arrival => Future[Option[ManifestLike]],
                                cacheStore: (Arrival, ManifestLike) => Future[Any],
                               )
                               (implicit ec: ExecutionContext): Iterable[Arrival] => Source[ManifestLike, NotUsed] = arrivals =>
    Source(arrivals.toList)
      .mapAsync(1) { arrival =>
        cacheLookup(arrival).flatMap {
          case Some(manifestLike) =>
            Metrics.counter("deskrecs.historic.cache.hit", 1)
            Future.successful(Option(manifestLike))
          case None =>
            Metrics.counter("deskrecs.historic.cache.miss", 1)
            manifestLookupService
              .maybeBestAvailableManifest(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
              .flatMap {
                case (_, Some(manifestLike)) =>
                  Metrics.counter("deskrecs.historic.cache.store", 1)
                  cacheStore(arrival, manifestLike).map(_ => Option(manifestLike))
                case (_, None) =>
                  Future.successful(None)
              }
              .recover {
                case t =>
                  log.warn(s"Failed to get historic manifest for ${arrival.unique}: ${t.getMessage}")
                  None
              }
        }
      }
      .collect { case Some(bam) => bam }

  def historicManifestsPaxProvider(destination: PortCode, manifestLookupService: ManifestLookupLike)
                                  (implicit ec: ExecutionContext): Arrival => Future[Option[ManifestPaxCount]] = arrival =>
    manifestLookupService
      .maybeHistoricManifestPax(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
      .map { case (_, maybeManifest) => maybeManifest }
      .recover {
        case t =>
          log.warn(s"Failed to get historic manifest for ${arrival.unique}: ${t.getMessage}")
          None
      }

  def arrivalsProvider(arrivalsActor: ActorRef)
                      (processingRequest: TerminalUpdateRequest)
                      (implicit timeout: Timeout, ec: ExecutionContext): Future[Source[List[Arrival], NotUsed]] =
    arrivalsActor
      .ask(GetFlightsForTerminalDateRange(processingRequest.start.millisSinceEpoch, processingRequest.end.millisSinceEpoch, processingRequest.terminal))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.map(_._2.apiFlight).toList))

  def flightsWithSplitsProvider(arrivalsActor: ActorRef)
                               (processingRequest: TerminalUpdateRequest)
                               (implicit timeout: Timeout, ec: ExecutionContext): Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    arrivalsActor
      .ask(GetFlightsForTerminalDateRange(processingRequest.start.millisSinceEpoch, processingRequest.end.millisSinceEpoch, processingRequest.terminal))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.values.toList))

  def passengersProvider(passengersActor: ActorRef)
                        (implicit timeout: Timeout, ec: ExecutionContext): (SDateLike, SDateLike, Terminal) => Future[Map[TQM, PassengersMinute]] =
    (start, end, terminal) =>
      passengersActor
        .ask(GetStateForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal))
        .mapTo[MinutesContainer[PassengersMinute, TQM]]
        .map(
          _.minutes.map(_.toMinute)
            .map { minute => (minute.key, minute) }
            .toMap
        )

  def staffMinutesProvider(staffActor: ActorRef)
                          (implicit timeout: Timeout, ec: ExecutionContext): (SDateLike, SDateLike, Terminal) => Future[List[Int]] =
    (start, end, terminal) => {
      staffActor
        .ask(GetStateForTerminalDateRange(start.millisSinceEpoch, end.millisSinceEpoch, terminal))
        .mapTo[MinutesContainer[StaffMinute, TM]]
        .map { container =>
          allMinutesForPeriod(terminal, start.millisSinceEpoch, end.millisSinceEpoch, container.indexed)
            .toList.sortBy(_.minute)
            .map(_.availableAtPcp)
        }
    }

  private def allMinutesForPeriod(terminal: Terminal,
                                  firstMillis: MillisSinceEpoch,
                                  lastMillis: MillisSinceEpoch,
                                  indexedStaff: Map[TM, StaffMinute]): Iterable[StaffMinute] =
    (firstMillis to lastMillis by MilliTimes.oneMinuteMillis).map(minute =>
      indexedStaff.getOrElse(TM(terminal, minute), StaffMinute(terminal, minute, 0, 0, 0, None))
    )
}
