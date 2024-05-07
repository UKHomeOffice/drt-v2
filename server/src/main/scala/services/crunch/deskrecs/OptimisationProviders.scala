package services.crunch.deskrecs

import actors.PartitionedPortStateActor.{GetFlights, GetStateForDateRange}
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
import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, UtcDate}

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
      .historicManifestPax(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
      .map { case (_, maybeManifest) => maybeManifest }
      .recover {
        case t =>
          log.warn(s"Failed to get historic manifest for ${arrival.unique}: ${t.getMessage}")
          None
      }

  def arrivalsProvider(arrivalsActor: ActorRef)
                      (processingRequest: ProcessingRequest)
                      (implicit timeout: Timeout, ec: ExecutionContext): Future[Source[List[Arrival], NotUsed]] =
    arrivalsActor
      .ask(GetFlights(processingRequest.start.millisSinceEpoch, processingRequest.end.millisSinceEpoch))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.map(_._2.apiFlight).toList))

  def flightsWithSplitsProvider(arrivalsActor: ActorRef)
                               (implicit timeout: Timeout, ec: ExecutionContext): ProcessingRequest => Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    request => arrivalsActor
      .ask(GetFlights(request.start.millisSinceEpoch, request.end.millisSinceEpoch))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.values.toList))

  def passengersProvider(passengersActor: ActorRef)
                        (processingRequest: ProcessingRequest)
                        (implicit timeout: Timeout, ec: ExecutionContext): Future[Map[TQM, PassengersMinute]] =
    passengersActor
      .ask(GetStateForDateRange(processingRequest.start.millisSinceEpoch, processingRequest.end.millisSinceEpoch))
      .mapTo[MinutesContainer[PassengersMinute, TQM]]
      .map(
        _.minutes.map(_.toMinute)
          .map { minute => (minute.key, minute) }
          .toMap
      )

  def staffMinutesProvider(staffActor: ActorRef, terminals: Iterable[Terminal])
                          (processingRequest: ProcessingRequest)
                          (implicit timeout: Timeout, ec: ExecutionContext): Future[Map[Terminal, List[Int]]] = {
    val start = processingRequest.start.millisSinceEpoch
    val end = processingRequest.end.millisSinceEpoch
    staffActor
      .ask(GetStateForDateRange(start, end))
      .mapTo[MinutesContainer[StaffMinute, TM]]
      .map { container =>
        allMinutesForPeriod(terminals, start, end, container.indexed)
          .groupBy(_.terminal)
          .map {
            case (t, minutes) =>
              val availableByMinute = minutes.toList.sortBy(_.minute).map(_.availableAtPcp)
              (t, availableByMinute)
          }
      }
  }

  private def allMinutesForPeriod(terminals: Iterable[Terminal],
                                  firstMillis: MillisSinceEpoch,
                                  lastMillis: MillisSinceEpoch,
                                  indexedStaff: Map[TM, StaffMinute]): Iterable[StaffMinute] =
    for {
      terminal <- terminals
      minute <- firstMillis to lastMillis by MilliTimes.oneMinuteMillis
    } yield {
      indexedStaff.getOrElse(TM(terminal, minute), StaffMinute(terminal, minute, 0, 0, 0, None))
    }
}
