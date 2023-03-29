package services.arrivals

import controllers.ArrivalGenerator
import drt.shared.ArrivalsDiff
import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Arrival, UniqueArrival}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.{A2, Terminal}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

import scala.collection.immutable.SortedMap

class EdiFlightAdjustmentsSpec extends Specification {

  def toArrivalsDiff(updated: List[Arrival] = List(), toRemove: List[Arrival] = List()): ArrivalsDiff = {
    ArrivalsDiff(SortedMap[UniqueArrival, Arrival]() ++ updated.map(a => a.unique -> a).toMap, toRemove.toSet)
  }

  val arrival1BaggageAtA1: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("1"))
  val arrival2BaggageAtA1: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("2"))
  val arrival3BaggageAtA1: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("3"))

  val arrival1BaggageAtA2: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("7"))
  val arrival2BaggageAtA2: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("8"))

  val arrivalAtA1: Arrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z")
  val arrival2AtA1: Arrival = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("A1"), schDt = "2020-07-17T15:00Z")
  val arrivalAtA2: Arrival = arrivalAtA1.copy(Terminal = A2)
  val arrival2AtA2: Arrival = arrival2AtA1.copy(Terminal = A2)

  val a1BaggageArrivals = List(arrival1BaggageAtA1, arrival2BaggageAtA1, arrival3BaggageAtA1)
  val a2BaggageArrivals = List(arrival1BaggageAtA2, arrival2BaggageAtA2)
  val a1Arrivals = List(arrivalAtA1, arrival2AtA1)
  val a2Arrivals = List(arrivalAtA2, arrival2AtA2)

  val notRedListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => false
  val redListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => true


  "Given incoming arrivals with baggage ids 1, 2 & 3, they should be assigned terminal A1" >> {
    val result = EdiArrivalsTerminalAdjustments.apply(a1BaggageArrivals)

    result === a1BaggageArrivals
  }

  "Given incoming arrivals with baggage ids 7 & 8, they should be assigned terminal A2" >> {
    val result = EdiArrivalsTerminalAdjustments.apply(a2BaggageArrivals)

    result === a2BaggageArrivals.map(_.copy(Terminal = A2))
  }
}
