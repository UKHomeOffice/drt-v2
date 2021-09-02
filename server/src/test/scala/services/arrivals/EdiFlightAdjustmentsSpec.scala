package services.arrivals

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.{A2, Terminal}
import drt.shared.api.Arrival
import drt.shared.redlist.RedListUpdates
import drt.shared.{ArrivalsDiff, PortCode, UniqueArrival}
import org.specs2.mutable.Specification

import scala.collection.immutable.SortedMap

class EdiFlightAdjustmentsSpec extends Specification {

  def toArrivalsDiff(updated: List[Arrival] = List(), toRemove: List[Arrival] = List()): ArrivalsDiff = {
    ArrivalsDiff(SortedMap[UniqueArrival, Arrival]() ++ updated.map(a => a.unique -> a).toMap, toRemove.toSet)
  }

  val arrivalAtA1: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z")
  val arrival2AtA1: Arrival = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("A1"), schDt = "2020-07-17T15:00Z")
  val arrivalAtA2: Arrival = arrivalAtA1.copy(Terminal = A2)
  val arrival2AtA2: Arrival = arrival2AtA1.copy(Terminal = A2)

  val a1Arrivals = List(arrivalAtA1, arrival2AtA1)
  val a2Arrivals = List(arrivalAtA2, arrival2AtA2)

  val notRedListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => false
  val redListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => true

  "Given incoming arrivals with non-red list flights, they should be assigned terminal A2" >> {
    val result = EdiArrivalsTerminalAdjustments(notRedListed).apply(a1Arrivals, RedListUpdates.empty)

    result === a1Arrivals.map(_.copy(Terminal = A2))
  }

  "Given incoming arrivals with red list flights, they should remain assigned terminal A1" >> {
    val result = EdiArrivalsTerminalAdjustments(redListed).apply(a1Arrivals, RedListUpdates.empty)

    val expected = a1Arrivals

    result === expected
  }
}
