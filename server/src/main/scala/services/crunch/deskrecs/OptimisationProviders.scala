package services.crunch.deskrecs

import actors.PartitionedPortStateActor.{GetFlightsForTerminalDateRange, GetStateForTerminalDateRange}
import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute, StaffMinute}
import drt.shared.TM
import manifests.ManifestLookupLike
import org.apache.pekko.NotUsed
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.pattern.ask
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.Timeout
import org.slf4j.{Logger, LoggerFactory}
import services.metrics.Metrics
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.models.{ManifestLike, TQM}
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
