package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.language.postfixOps

object FlightsApi {

  case class Flights(flights: Iterable[Arrival])

  object Flights {
    val empty: Flights = Flights(Seq())
  }

  object PaxForArrivals {
    val empty: PaxForArrivals = PaxForArrivals(Map())

    def from(arrivals: Iterable[Arrival], feedSource: FeedSource): PaxForArrivals =
      PaxForArrivals(arrivals
        .map { arrival =>
          val histApiPax = arrival.PassengerSources.view.filterKeys(_ == feedSource).toMap
          (arrival.unique, histApiPax)
        }
        .collect { case (key, nonEmptyPax) if nonEmptyPax.nonEmpty => (key, nonEmptyPax) }
        .toMap
      )
  }

  case class PaxForArrivals(pax: Map[UniqueArrival, Map[FeedSource, Passengers]]) extends FlightUpdates {
    def diff(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): ArrivalsDiff = {
      val updatedFlights = pax.map {
        case (key, newPax) =>
          flights.flights.get(key)
            .map { fws =>
              val updatedPax = newPax.filter {
                case (source, pax) =>
                  val existingPax = fws.apiFlight.PassengerSources.get(source)
                  existingPax.isEmpty || existingPax.exists(_ != pax)
              }
              (fws, updatedPax)
            }
            .collect {
              case (fws, updatedPax) if updatedPax.nonEmpty =>
                val mergedPax = fws.apiFlight.PassengerSources ++ updatedPax
                fws.apiFlight.copy(PassengerSources = mergedPax)
            }
      }.collect { case Some(flight) => flight }

      ArrivalsDiff(updatedFlights, List())
    }
  }

  case object RemoveSplits extends FlightUpdates

  case class RemoveSplitsForDateRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch) extends FlightUpdates

}
