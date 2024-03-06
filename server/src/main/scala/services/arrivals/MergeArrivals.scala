package services.arrivals

import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.time.UtcDate

import scala.concurrent.{ExecutionContext, Future}

object MergeArrivals {
  def apply(existingMerged: UtcDate => Future[Set[UniqueArrival]],
            arrivalSources: Seq[UtcDate => Future[(Boolean, Map[UniqueArrival, Arrival])]],
           )
           (implicit ec: ExecutionContext): UtcDate => Future[(Map[UniqueArrival, Arrival], Set[UniqueArrival])] =
    (date: UtcDate) => {
      for {
        arrivalSets <- Future.sequence(arrivalSources.map(_(date)))
        existing <- existingMerged(date)
      } yield {
        mergeSets(existing, arrivalSets)
      }
    }

  def mergeSets(existingMerged: Set[UniqueArrival],
                arrivalSets: Seq[(Boolean, Map[UniqueArrival, Arrival])],
               ): (Map[UniqueArrival, Arrival], Set[UniqueArrival]) = {
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
    val removed = existingMerged -- newMerged.keySet
    (newMerged, removed)
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
}
