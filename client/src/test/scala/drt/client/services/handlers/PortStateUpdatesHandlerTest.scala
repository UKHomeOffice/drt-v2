package drt.client.services.handlers

import drt.client.components.ArrivalGenerator
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.PortStateUpdatesHandler.splitsToManifestKeys
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.models.ManifestKey
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike
import utest._


object PortStateUpdatesHandlerTest extends TestSuite {
  val scheduled: SDateLike = SDate("2025-05-01T12:00")
  val arrival: Arrival = ArrivalGenerator.arrival(
    feedSource = LiveFeedSource,
    iata = "BA100", schDt = scheduled.toISOString, origin = PortCode("JFK"), previousPort = Option(PortCode("CDG")),
  )
  val splits: Splits = Splits(Set.empty, ApiSplitsWithHistoricalEGateAndFTPercentages, None)
  val flight: ApiFlightWithSplits = ApiFlightWithSplits(arrival, Set(splits))
  val flights: Map[UniqueArrival, ApiFlightWithSplits] = Map(flight.unique -> flight)

  val tests: Tests = Tests {
    test("manifestArrivalKey should") {
      test("return a ManifestKey with the PreviousPort as the origin when there's a matching arrival with a previous port") {
        val key = PortStateUpdatesHandler.manifestArrivalKey(flight.unique, flights)

        assert(key.origin == PortCode("CDG"))
        assert(key == ManifestKey(flight.apiFlight))
      }

      test("return a ManifestKey with the Origin when there's no matching arrival") {
        val key = PortStateUpdatesHandler.manifestArrivalKey(flight.unique, Map.empty)

        assert(key.origin == flight.unique.origin)
        assert(key == ManifestKey(arrival.Origin, arrival.VoyageNumber, arrival.Scheduled))
      }
    }


    test("splitsToManifestKeys should") {
      test("return ManifestKeys that use the previous port from a matched flight") {
        val splitsForArrivals = SplitsForArrivals(Map(flight.unique -> Set(splits)))
        val arrivalKeys = splitsToManifestKeys(Iterable(splitsForArrivals), flights, Set.empty)

        assert(arrivalKeys.map(_.origin) == Set(PortCode("CDG")))
        assert(arrivalKeys == Set(ManifestKey(flight.apiFlight)))
      }

      test("return ManifestKeys based on splits UniqueArrival keys when there is no matching flight") {
        val splitsForArrivals = SplitsForArrivals(Map(flight.unique -> Set(splits)))
        val arrivalKeys = splitsToManifestKeys(Iterable(splitsForArrivals), Map.empty, Set.empty)

        assert(arrivalKeys == Set(ManifestKey(arrival.Origin, arrival.VoyageNumber, arrival.Scheduled)))
      }
    }
  }
}
