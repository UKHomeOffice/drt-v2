package drt.client.services.handlers

import drt.client.components.ArrivalGenerator
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.PortStateUpdatesHandler.splitsToManifestArrivalKeys
import drt.shared.ArrivalKey
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.time.SDateLike
import utest._


object PortStateUpdatesHandlerTest extends TestSuite {
  val scheduled: SDateLike = SDate("1970-01-01T00:00")
  val arrival: Arrival = ArrivalGenerator.arrival(
    feedSource = LiveFeedSource,
    iata = "BA100", schDt = scheduled.toISOString, origin = PortCode("JFK"), previousPort = Option(PortCode("CDG")),
  )
  val splits: Splits = Splits(Set.empty, ApiSplitsWithHistoricalEGateAndFTPercentages, None)
  val flight: ApiFlightWithSplits = ApiFlightWithSplits(arrival, Set(splits))
  val flights: Map[UniqueArrival, ApiFlightWithSplits] = Map(flight.unique -> flight)

  val tests: Tests = Tests {
    test("manifestArrivalKey should give us an ArrivalKey with the PreviousPort as the origin when there's a matching arrival with a previous port") {
      val key = PortStateUpdatesHandler.manifestArrivalKey(flight.unique, flights)

      assert(key.origin == PortCode("CDG"))
      assert(key == ArrivalKey.forManifest(flight.apiFlight))
    }

    test("splitsToManifestArrivalKeys should take incoming splits and return ArrivalKeys that use the previous port where available") {
      val splitsForArrivals = SplitsForArrivals(Map(flight.unique -> Set(splits)))
      val arrivalKeys = splitsToManifestArrivalKeys(Iterable(splitsForArrivals), flights, Set.empty)

      assert(arrivalKeys.map(_.origin) == Set(PortCode("CDG")))
      assert(arrivalKeys == Set(ArrivalKey.forManifest(flight.apiFlight)))
    }

    test("splitsToManifestArrivalKeys should take incoming splits and return regular ArrivalKeys where there is no matching flight") {
      val splitsForArrivals = SplitsForArrivals(Map(flight.unique -> Set(splits)))
      val arrivalKeys = splitsToManifestArrivalKeys(Iterable(splitsForArrivals), Map.empty, Set.empty)

      assert(arrivalKeys == Set(ArrivalKey(flight.apiFlight)))
    }
  }
}
