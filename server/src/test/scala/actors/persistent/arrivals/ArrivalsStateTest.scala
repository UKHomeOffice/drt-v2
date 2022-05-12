package actors.persistent.arrivals

import drt.shared.ArrivalGenerator
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.LiveFeedSource

import scala.collection.immutable.SortedMap

class ArrivalsStateTest extends Specification {
  "Given an empty arrivals state" >> {
    "When I send it an arrival then it should contain that arrival" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001")
      val result = ArrivalsState.empty(LiveFeedSource) ++ Seq(arrival)
      result === ArrivalsState(SortedMap[UniqueArrival, Arrival](arrival.unique -> arrival), LiveFeedSource, None)
    }

    "When I send it an arrival with a gate followed by the same arrival without a gate then it should retain the gate" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001")
      val arrivalWithGate = arrival.copy(Gate = Option("1a"))
      val result = ArrivalsState.empty(LiveFeedSource) ++ Seq(arrivalWithGate) ++ Seq(arrival)
      result === ArrivalsState(SortedMap[UniqueArrival, Arrival](arrival.unique -> arrivalWithGate), LiveFeedSource, None)
    }

    "When I send it an arrival with a stand followed by the same arrival without a stand then it should retain the stand" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001")
      val arrivalWithStand = arrival.copy(Stand = Option("1a"))
      val result = ArrivalsState.empty(LiveFeedSource) ++ Seq(arrivalWithStand) ++ Seq(arrival)
      result === ArrivalsState(SortedMap[UniqueArrival, Arrival](arrival.unique -> arrivalWithStand), LiveFeedSource, None)
    }

    "When I send it an arrival with a baggage reclaim id followed by the same arrival without a baggage reclaim id then it should retain the baggage reclaim id" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001")
      val arrivalWithBaggageReclaimId = arrival.copy(BaggageReclaimId = Option("1a"))
      val result = ArrivalsState.empty(LiveFeedSource) ++ Seq(arrivalWithBaggageReclaimId) ++ Seq(arrival)
      result === ArrivalsState(SortedMap[UniqueArrival, Arrival](arrival.unique -> arrivalWithBaggageReclaimId), LiveFeedSource, None)
    }

    "When I send it an arrival with a red list pax followed by the same arrival without a red list pax then it should retain the red list pax" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001")
      val arrivalWithRedListPax = arrival.copy(RedListPax = Option(1))
      val result = ArrivalsState.empty(LiveFeedSource) ++ Seq(arrivalWithRedListPax) ++ Seq(arrival)
      result === ArrivalsState(SortedMap[UniqueArrival, Arrival](arrival.unique -> arrivalWithRedListPax), LiveFeedSource, None)
    }
  }
}
