package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}

import scala.collection.immutable.{Map => IMap}
import scala.language.postfixOps

object FlightsApi {

  case class Flights(flights: Iterable[Arrival])

  object Flights {
    val empty: Flights = Flights(Seq())
  }

  case object NoFlightUpdates extends FlightUpdates

  object PaxForArrivals {
    val empty: PaxForArrivals = PaxForArrivals(Map())

    def from(arrivals: Iterable[Arrival], feedSource: FeedSource): PaxForArrivals =
      PaxForArrivals(arrivals
        .map { arrival =>
          val histApiPax = arrival.TotalPax.filter(_.feedSource == feedSource)
          (arrival.unique, histApiPax)
        }
        .collect { case (key, nonEmptyPax) if nonEmptyPax.nonEmpty => (key, nonEmptyPax) }
        .toMap
      )
  }

  case class PaxForArrivals(pax: Map[UniqueArrival, Set[TotalPaxSource]]) extends FlightUpdates {
    def diff(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): FlightsWithSplitsDiff = {
      val updatedFlights = pax.map {
        case (key, newPax) =>
          flights.flights.get(key)
            .map(fws => (fws, newPax.diff(fws.apiFlight.TotalPax)))
            .collect {
              case (fws, updatedPax) if updatedPax.nonEmpty =>
                val updatedSources = updatedPax.map(_.feedSource)
                val mergedPax = fws.apiFlight.TotalPax.filterNot(s => updatedSources.contains(s.feedSource)) ++ updatedPax
                val updatedArrival = fws.apiFlight.copy(TotalPax = mergedPax)
                fws.copy(apiFlight = updatedArrival, lastUpdated = Option(nowMillis))
            }
      }.collect { case Some(flight) => flight }

      FlightsWithSplitsDiff(updatedFlights, List())
    }
  }

  object SplitsForArrivals {
    val empty: SplitsForArrivals = SplitsForArrivals(Map())
  }

  case class SplitsForArrivals(splits: Map[UniqueArrival, Set[Splits]]) extends FlightUpdates {
    def diff(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): FlightsWithSplitsDiff = {
      val updatedFlights = splits
        .map {
          case (key, newSplits) =>
            flights.flights.get(key)
              .map(fws => (fws, newSplits.diff(fws.splits)))
              .collect {
                case (fws, updatedSplits) if updatedSplits.nonEmpty =>
                  val updatedSources = updatedSplits.map(_.source)
                  val mergedSplits = fws.splits.filterNot(s => updatedSources.contains(s.source)) ++ updatedSplits
                  val updatedArrival = mergedSplits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages) match {
                    case None =>
                      fws.apiFlight
                    case Some(liveSplit) =>
                      val totalPax = Math.round(liveSplit.totalExcludingTransferPax).toInt
                      val sources = fws.apiFlight.FeedSources + ApiFeedSource
                      val totalPaxSources = fws.apiFlight.TotalPax ++ Set(TotalPaxSource(Some(totalPax), ApiFeedSource))
                      fws.apiFlight.copy(
                        ApiPax = Option(totalPax),
                        FeedSources = sources,
                        TotalPax = totalPaxSources
                      )
                  }

                  fws.copy(apiFlight = updatedArrival, splits = mergedSplits, lastUpdated = Option(nowMillis))
              }
        }
        .collect { case Some(flight) => flight }

      FlightsWithSplitsDiff(updatedFlights, List())
    }

    def ++(tuple: (UniqueArrival, Set[Splits])): IMap[UniqueArrival, Set[Splits]] = splits + tuple
  }

  case object RemoveSplits extends FlightUpdates

  case class RemoveSplitsForDateRange(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch) extends FlightUpdates

}
