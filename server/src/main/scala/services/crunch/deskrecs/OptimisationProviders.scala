package services.crunch.deskrecs

import actors.PartitionedPortStateActor.{GetFlights, GetStateForDateRange}
import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{TM, TQM}
import manifests.ManifestLookupLike
import manifests.passengers.ManifestLike
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.crunch.deskrecs.DynamicRunnableDeskRecs.{HistoricManifestsPaxProvider, HistoricManifestsProvider}
import services.crunch.deskrecs.RunnableOptimisation.ProcessingRequest
import services.graphstages.Crunch.LoadMinute
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{MilliTimes, UtcDate}

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContext, Future}

object OptimisationProviders {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def historicManifestsProvider(destination: PortCode,
                                manifestLookupService: ManifestLookupLike,
                                cacheLookup: Arrival => Future[Option[ManifestLike]],
                                cacheStore: (Arrival, ManifestLike) => Future[Any],
                               )
                               (implicit mat: Materializer, ec: ExecutionContext): HistoricManifestsProvider = arrivals =>
    Source(arrivals.toList)
      .mapAsync(1) { arrival =>
        cacheLookup(arrival).flatMap {
          case Some(manifestLike) =>
            log.info(s"cache hit for ${arrival.unique}")
            Future.successful(Option(manifestLike))
          case None =>
            log.info(s"cache miss for ${arrival.unique}")
            manifestLookupService
              .maybeBestAvailableManifest(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
              .flatMap {
                case (_, Some(manifestLike)) =>
                  cacheStore(arrival, manifestLike).map(_ => Option(manifestLike))
                case (_, None) =>
                  Future.successful(None)
              }
              .recover {
                case t =>
                  log.warn(s"Failed to get historic manifest for ${arrival.unique}")
                  None
              }
        }
      }
      .collect { case Some(bam) => bam }

  def historicManifestsPaxProvider(destination: PortCode, manifestLookupService: ManifestLookupLike)
                                  (implicit mat: Materializer, ec: ExecutionContext): HistoricManifestsPaxProvider = arrival =>
    manifestLookupService
      .historicManifestPax(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
      .map { case (_, maybeManifest) => maybeManifest }
      .recover {
        case t =>
          log.warn(s"Failed to get historic manifest for ${arrival.unique}")
          None
      }

  def arrivalsProvider(arrivalsActor: ActorRef)
                      (crunchRequest: ProcessingRequest)
                      (implicit timeout: Timeout, ec: ExecutionContext): Future[Source[List[Arrival], NotUsed]] =
    arrivalsActor
      .ask(GetFlights(crunchRequest.start.millisSinceEpoch, crunchRequest.end.millisSinceEpoch))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.map(_._2.apiFlight).toList))

  def flightsWithSplitsProvider(arrivalsActor: ActorRef)
                               (crunchRequest: ProcessingRequest)
                               (implicit timeout: Timeout, ec: ExecutionContext): Future[Source[List[ApiFlightWithSplits], NotUsed]] =
    arrivalsActor
      .ask(GetFlights(crunchRequest.start.millisSinceEpoch, crunchRequest.end.millisSinceEpoch))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.values.toList))

  def liveManifestsProvider(manifestsActor: ActorRef)
                           (crunchRequest: ProcessingRequest)
                           (implicit timeout: Timeout): Future[Source[VoyageManifests, NotUsed]] =
    manifestsActor
      .ask(GetStateForDateRange(crunchRequest.start.millisSinceEpoch, crunchRequest.end.millisSinceEpoch))
      .mapTo[Source[VoyageManifests, NotUsed]]

  def loadsProvider(queuesActor: ActorRef)
                   (crunchRequest: ProcessingRequest)
                   (implicit timeout: Timeout, ec: ExecutionContext): Future[Map[TQM, LoadMinute]] =
    queuesActor
      .ask(GetStateForDateRange(crunchRequest.start.millisSinceEpoch, crunchRequest.end.millisSinceEpoch))
      .mapTo[MinutesContainer[CrunchMinute, TQM]]
      .map(
        _.minutes.map(_.toMinute)
          .map { minute => (minute.key, LoadMinute(minute)) }
          .toMap
      )

  def staffMinutesProvider(staffActor: ActorRef, terminals: Iterable[Terminal])
                          (crunchRequest: ProcessingRequest)
                          (implicit timeout: Timeout, ec: ExecutionContext): Future[Map[Terminal, List[Int]]] = {
    val start = crunchRequest.start.millisSinceEpoch
    val end = crunchRequest.end.millisSinceEpoch
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
