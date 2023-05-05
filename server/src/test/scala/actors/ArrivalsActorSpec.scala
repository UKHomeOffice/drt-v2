package actors

import controllers.ArrivalGenerator
import org.specs2.mutable.Specification
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, UniqueArrival}
import uk.gov.homeoffice.drt.ports.{AclFeedSource, LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.T1

import scala.collection.immutable.SortedMap

class ArrivalsActorSpec extends Specification {
  val arrival1: Arrival = ArrivalGenerator
    .arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"), schDt = "2019-01-01T00:05",
      totalPax = Map(AclFeedSource -> Passengers(Option(100),None)))
  val arrival2: Arrival = ArrivalGenerator
    .arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35",
      totalPax = Map(AclFeedSource -> Passengers(Option(200),None)))
  val arrival3: Arrival = ArrivalGenerator
    .arrival(iata = "BA0003", terminal = T1, origin = PortCode("ZZZ"), schDt = "2019-01-01T00:55",
      totalPax = Map(AclFeedSource -> Passengers(Option(250),None)))

  "Given no existing arrivals and one incoming " +
  "When I ask for removals and updates " +
  "I should see one update and no removals" >> {
    val existing = SortedMap[UniqueArrival, Arrival]()
    val incoming = arrivalsToKeysArrivals(List(arrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.isEmpty && updates === List(arrival1)
  }

  "Given one existing arrivals and one incoming matching the existing arrival " +
  "When I ask for removals and updates " +
  "I should see no updates and no removals" >> {
    val existing = SortedMap[UniqueArrival, Arrival]() ++ arrivalsToKeysArrivals(List(arrival1))
    val incoming = arrivalsToKeysArrivals(List(arrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.isEmpty && updates.isEmpty
  }

  "Given two existing arrivals and one incoming which matches an existing arrival " +
  "When I ask for removals and updates " +
  "I should see no updates and one removal for the arrival no longer in the incoming set" >> {
    val existing = SortedMap[UniqueArrival, Arrival]() ++ arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List(arrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival2.unique) && updates.isEmpty
  }

  "Given two existing arrivals and one incoming which matches an existing arrival except with an update " +
  "When I ask for removals and updates " +
  "I should see one update and one removal for the arrival no longer in the incoming set" >> {
    val updatedArrival1 = arrival1.copy(TotalPax = Map(AclFeedSource -> Passengers(Option(150),None)))

    val existing = SortedMap[UniqueArrival, Arrival]() ++ arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List(updatedArrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival2.unique) && updates.toList.contains(updatedArrival1)
  }

  "Given two existing arrivals and no incoming arrivals" +
  "When I ask for removals and updates " +
  "I should see no updates and two removals" >> {
    val existing = SortedMap[UniqueArrival, Arrival]() ++ arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List()).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival1.unique) && removals.contains(arrival2.unique) && updates.isEmpty
  }

  "Given 2 existing arrivals and two incoming arrivals, one an update and one new" +
  "When I ask for removals and updates " +
  "I should see two updates (a new and 1 update) and one removal" >> {
    val updatedArrival2 = arrival2.copy(TotalPax = Map(AclFeedSource -> Passengers(Option(325),None)))

    val existing = SortedMap[UniqueArrival, Arrival]() ++ arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List(updatedArrival2, arrival3)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival1.unique) && updates === List(updatedArrival2, arrival3)
  }

  private def arrivalsToKeysArrivals(arrivals: List[Arrival]): Seq[(UniqueArrival, Arrival)] = arrivals.map(a => (a.unique, a))
}
