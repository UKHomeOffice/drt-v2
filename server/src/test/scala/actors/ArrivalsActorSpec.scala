package actors

import controllers.ArrivalGenerator
import drt.shared.Terminals.T1
import drt.shared.{Arrival, PortCode, UniqueArrival}
import org.specs2.mutable.Specification
import services.graphstages.Crunch

import scala.collection.mutable

class ArrivalsActorSpec extends Specification {
  val arrival1: Arrival = ArrivalGenerator.arrival(iata = "BA0001", terminal = T1, origin = PortCode("JFK"), schDt = "2019-01-01T00:05", actPax = Option(100))
  val arrival2: Arrival = ArrivalGenerator.arrival(iata = "BA0002", terminal = T1, origin = PortCode("ABC"), schDt = "2019-01-01T00:35", actPax = Option(200))
  val arrival3: Arrival = ArrivalGenerator.arrival(iata = "BA0003", terminal = T1, origin = PortCode("ZZZ"), schDt = "2019-01-01T00:55", actPax = Option(250))

  "Given no existing arrivals and one incoming " +
  "When I ask for removals and updates " +
  "I should see one update and no removals" >> {
    val existing = mutable.SortedMap[UniqueArrival, Arrival]()
    val incoming = arrivalsToKeysArrivals(List(arrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.isEmpty && updates === mutable.Set(arrival1)
  }

  "Given one existing arrivals and one incoming matching the existing arrival " +
  "When I ask for removals and updates " +
  "I should see no updates and no removals" >> {
    val existing = mutable.SortedMap[UniqueArrival, Arrival]() ++= arrivalsToKeysArrivals(List(arrival1))
    val incoming = arrivalsToKeysArrivals(List(arrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.isEmpty && updates.isEmpty
  }

  "Given two existing arrivals and one incoming which matches an existing arrival " +
  "When I ask for removals and updates " +
  "I should see no updates and one removal for the arrival no longer in the incoming set" >> {
    val existing = mutable.SortedMap[UniqueArrival, Arrival]() ++= arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List(arrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival2.unique) && updates.isEmpty
  }

  "Given two existing arrivals and one incoming which matches an existing arrival except with an update " +
  "When I ask for removals and updates " +
  "I should see one update and one removal for the arrival no longer in the incoming set" >> {
    val updatedArrival1 = arrival1.copy(ActPax = Option(150))

    val existing = mutable.SortedMap[UniqueArrival, Arrival]() ++= arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List(updatedArrival1)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival2.unique) && updates.contains(updatedArrival1)
  }

  "Given two existing arrivals and no incoming arrivals" +
  "When I ask for removals and updates " +
  "I should see no updates and two removals" >> {
    val existing = mutable.SortedMap[UniqueArrival, Arrival]() ++= arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List()).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival1.unique) && removals.contains(arrival2.unique) && updates.isEmpty
  }

  "Given 2 existing arrivals and two incoming arrivals, one an update and one new" +
  "When I ask for removals and updates " +
  "I should see two updates (a new and 1 update) and one removal" >> {
    val updatedArrival2 = arrival2.copy(ActPax = Option(325))

    val existing = mutable.SortedMap[UniqueArrival, Arrival]() ++= arrivalsToKeysArrivals(List(arrival1, arrival2))
    val incoming = arrivalsToKeysArrivals(List(updatedArrival2, arrival3)).toMap

    val (removals, updates) = Crunch.baseArrivalsRemovalsAndUpdates(incoming, existing)

    removals.contains(arrival1.unique) && updates === mutable.Set(updatedArrival2, arrival3)
  }

  private def arrivalsToKeysArrivals(arrivals: List[Arrival]): Seq[(UniqueArrival, Arrival)] = arrivals.map(a => (a.unique, a))
}
