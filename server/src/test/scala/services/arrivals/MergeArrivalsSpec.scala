package services.arrivals

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
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

  def mergeArrivals(previous: Arrival, next: Arrival): Arrival =
    next.copy(
      Operator = next.Operator.orElse(previous.Operator),
      CarrierCode = previous.CarrierCode,
      Estimated = next.Estimated.orElse(previous.Estimated),
      Actual = next.Actual.orElse(previous.Actual),
      EstimatedChox = next.EstimatedChox.orElse(previous.EstimatedChox),
      ActualChox = next.ActualChox.orElse(previous.ActualChox),
      Gate = next.Gate.orElse(previous.Gate),
      Stand = next.Stand.orElse(previous.Stand),
      MaxPax = next.MaxPax.orElse(previous.MaxPax),
      RunwayID = next.RunwayID.orElse(previous.RunwayID),
      BaggageReclaimId = next.BaggageReclaimId.orElse(previous.BaggageReclaimId),
      FeedSources = previous.FeedSources ++ next.FeedSources,
      CarrierScheduled = previous.CarrierScheduled.orElse(next.CarrierScheduled),
      ScheduledDeparture = previous.ScheduledDeparture.orElse(next.ScheduledDeparture),
      RedListPax = previous.RedListPax.orElse(next.RedListPax),
      PassengerSources = previous.PassengerSources ++ next.PassengerSources,
    )
}

class MergeArrivalsSpec extends AnyWordSpec with Matchers {

}
