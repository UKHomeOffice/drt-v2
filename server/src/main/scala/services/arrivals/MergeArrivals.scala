package services.arrivals

import akka.NotUsed
import akka.stream.scaladsl.Flow
import drt.shared.ArrivalKey
import uk.gov.homeoffice.drt.actor.commands.{MergeArrivalsRequest, ProcessingRequest}
import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, UniqueArrival}
import uk.gov.homeoffice.drt.ports.ApiFeedSource
import uk.gov.homeoffice.drt.time.{DateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object MergeArrivals {
  def apply(existingMerged: UtcDate => Future[Set[UniqueArrival]],
            arrivalSources: Seq[DateLike => Future[(Boolean, Map[UniqueArrival, Arrival])]],
            adjustments: Arrival => Arrival,
           )
           (implicit ec: ExecutionContext): UtcDate => Future[ArrivalsDiff] =
    (date: UtcDate) => {
      for {
        arrivalSets <- Future.sequence(arrivalSources.map(_(date)))
        existing <- existingMerged(date)
      } yield mergeSets(existing, arrivalSets, adjustments)
    }

  def mergeSets(existingMerged: Set[UniqueArrival],
                arrivalSets: Seq[(Boolean, Map[UniqueArrival, Arrival])],
                adjustments: Arrival => Arrival,
               ): ArrivalsDiff = {
    val newMerged = arrivalSets.toList match {
      case (_, startSet) :: otherSets =>
        otherSets.foldLeft(startSet) {
          case (acc, (isPrimary, arrivals)) =>
            arrivals.foldLeft(acc) {
              case (acc, (uniqueArrival, nextArrival)) =>
                acc.get(uniqueArrival) match {
                  case Some(existingArrival) =>
                    acc + (uniqueArrival -> mergeArrivals(existingArrival, nextArrival))
                  case None =>
                    if (isPrimary) acc + (uniqueArrival -> nextArrival)
                    else acc
                }
            }
        }
    }
    val mergedAndAdjusted = newMerged.values.map(adjustments).map(a => a.unique -> a).toMap
    val removed = existingMerged -- mergedAndAdjusted.keySet
    ArrivalsDiff(mergedAndAdjusted, removed)
  }

  def mergeArrivals(current: Arrival, next: Arrival): Arrival =
    next.copy(
      Operator = next.Operator.orElse(current.Operator),
      CarrierCode = current.CarrierCode,
      Estimated = next.Estimated.orElse(current.Estimated),
      Actual = next.Actual.orElse(current.Actual),
      EstimatedChox = next.EstimatedChox.orElse(current.EstimatedChox),
      ActualChox = next.ActualChox.orElse(current.ActualChox),
      Gate = next.Gate.orElse(current.Gate),
      Stand = next.Stand.orElse(current.Stand),
      MaxPax = next.MaxPax.orElse(current.MaxPax),
      RunwayID = next.RunwayID.orElse(current.RunwayID),
      BaggageReclaimId = next.BaggageReclaimId.orElse(current.BaggageReclaimId),
      FeedSources = next.FeedSources ++ current.FeedSources,
      CarrierScheduled = next.CarrierScheduled.orElse(current.CarrierScheduled),
      ScheduledDeparture = next.ScheduledDeparture.orElse(current.ScheduledDeparture),
      RedListPax = next.RedListPax.orElse(current.RedListPax),
      PassengerSources = next.PassengerSources ++ current.PassengerSources,
      FlightCodeSuffix = next.FlightCodeSuffix.orElse(current.FlightCodeSuffix),
    )

  def processingRequestToArrivalsDiff(mergeArrivalsForDate: UtcDate => Future[ArrivalsDiff],
                                      setPcpTimes: ArrivalsDiff => Future[ArrivalsDiff],
                                      addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff],
                                      updateAggregatedArrivals: ArrivalsDiff => Unit,
                                      hasApi: UtcDate => Future[Seq[ArrivalKey]],
                                     )
                                     (implicit ec: ExecutionContext): Flow[ProcessingRequest, ArrivalsDiff, NotUsed] = {
    Flow[ProcessingRequest]
      .collect {
        case request: MergeArrivalsRequest => request
      }
      .mapAsync(1) {
        request =>
          mergeArrivalsForDate(request.date)
            .flatMap(setPcpTimes)
            .flatMap(addArrivalPredictions)
            .flatMap {
              diff =>
                hasApi(request.date).map {
                  case Seq() => diff
                  case keys =>
                    diff.copy(
                      toUpdate = diff.toUpdate.values.map { a =>
                        if (keys.contains(ArrivalKey(a))) a.copy(FeedSources = a.FeedSources + ApiFeedSource)
                        else a
                      }.map(a => a.unique -> a).toMap
                    )
                }
            }
      }
      .wireTap(updateAggregatedArrivals)
  }
}
