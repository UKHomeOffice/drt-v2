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
import drt.shared.api.Arrival
import drt.shared.dates.UtcDate
import drt.shared.{MilliTimes, TM, TQM}
import manifests.ManifestLookupLike
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.SDate
import services.crunch.deskrecs.DynamicRunnableDeskRecs.HistoricManifestsProvider
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch.LoadMinute
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContext, Future}

object OptimisationProviders {
  def historicManifestsProvider(destination: PortCode, manifestLookupService: ManifestLookupLike)
                               (implicit mat: Materializer, ec: ExecutionContext): HistoricManifestsProvider = arrivals => {
    Future(
      Source(arrivals.toList)
        .mapAsync(1) { arrival =>
          manifestLookupService.maybeBestAvailableManifest(destination, arrival.Origin, arrival.VoyageNumber, SDate(arrival.Scheduled))
        }
        .collect { case (_, Some(bam)) => bam }
    )
  }

  def arrivalsProvider(arrivalsActor: ActorRef)
                      (crunchRequest: CrunchRequest)
                      (implicit timeout: Timeout, ec: ExecutionContext): Future[Source[List[Arrival], NotUsed]] =
    arrivalsActor
      .ask(GetFlights(crunchRequest.start.millisSinceEpoch, crunchRequest.end.millisSinceEpoch))
      .mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]]
      .map(_.map(_._2.flights.map(_._2.apiFlight).toList))

  def liveManifestsProvider(manifestsActor: ActorRef)
                           (crunchRequest: CrunchRequest)
                           (implicit timeout: Timeout): Future[Source[VoyageManifests, NotUsed]] =
    manifestsActor
      .ask(GetStateForDateRange(crunchRequest.start.millisSinceEpoch, crunchRequest.end.millisSinceEpoch))
      .mapTo[Source[VoyageManifests, NotUsed]]

  def loadsProvider(queuesActor: ActorRef)
                   (crunchRequest: CrunchRequest)
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
                          (crunchRequest: CrunchRequest)
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
