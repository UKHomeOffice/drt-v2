package services.arrivals

import controllers.ArrivalGenerator
import drt.shared.CrunchApi.MillisSinceEpoch
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.LiveArrival
import uk.gov.homeoffice.drt.ports.{LiveFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.{A2, Terminal}
import uk.gov.homeoffice.drt.redlist.RedListUpdates

class EdiFlightAdjustmentsSpec extends Specification {
  val arrival1BaggageAtA1: LiveArrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("1"))
  val arrival2BaggageAtA1: LiveArrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("2"))
  val arrival3BaggageAtA1: LiveArrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("3"))

  val arrival1BaggageAtA2: LiveArrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("7"))
  val arrival2BaggageAtA2: LiveArrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z", baggageReclaimId = Option("8"))

  val arrivalAtA1: LiveArrival = ArrivalGenerator.arrival(iata = "TST100", terminal = Terminal("A1"), schDt = "2020-07-17T14:00Z")
  val arrival2AtA1: LiveArrival = ArrivalGenerator.arrival(iata = "TST200", terminal = Terminal("A1"), schDt = "2020-07-17T15:00Z")
  val arrivalAtA2: LiveArrival = arrivalAtA1.copy(terminal = A2)
  val arrival2AtA2: LiveArrival = arrival2AtA1.copy(terminal = A2)

  val a1BaggageArrivals: Seq[LiveArrival] = List(arrival1BaggageAtA1, arrival2BaggageAtA1, arrival3BaggageAtA1)
  val a2BaggageArrivals: Seq[LiveArrival] = List(arrival1BaggageAtA2, arrival2BaggageAtA2)
  val a1Arrivals: Seq[LiveArrival] = List(arrivalAtA1, arrival2AtA1)
  val a2Arrivals: Seq[LiveArrival] = List(arrivalAtA2, arrival2AtA2)

  val notRedListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => false
  val redListed: (PortCode, MillisSinceEpoch, RedListUpdates) => Boolean = (_, _, _) => true


  "Given incoming arrivals with baggage ids 1, 2 & 3, they should be assigned terminal A1" >> {
    val result = a1BaggageArrivals.map(a => EdiArrivalsTerminalAdjustments.adjust(a.toArrival(LiveFeedSource)))

    result === a1BaggageArrivals
  }

  "Given incoming arrivals with baggage ids 7 & 8, they should be assigned terminal A2" >> {
    val result = a2BaggageArrivals.map(a=> EdiArrivalsTerminalAdjustments.adjust(a.toArrival(LiveFeedSource)))

    result === a2BaggageArrivals.map(_.copy(terminal = A2))
  }
}
