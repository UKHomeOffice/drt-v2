package drt.shared

import drt.shared.FlightsApi.PaxForArrivals
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.TotalPaxSource
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, HistoricApiFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.T1

class PaxForArrivalsTest extends Specification {
  "When I ask to extract the historic api nos" >> {
    "Given an arrival with no passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", sch = 0L, terminal = T1, origin = PortCode("ABC"), totalPax = Set())
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals.empty
      }
    }

    "Given an arrival with live api passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", sch = 0L, terminal = T1, origin = PortCode("ABC"),
        totalPax = Set(TotalPaxSource(Option(100), ApiFeedSource)))
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals.empty
      }
    }

    "Given an arrival with live & historic api passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", sch = 0L, terminal = T1, origin = PortCode("ABC"),
        totalPax = Set(TotalPaxSource(Option(100), ApiFeedSource), TotalPaxSource(Option(100), HistoricApiFeedSource)))
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals(Map(
          arrival.unique -> Set(TotalPaxSource(Option(100), HistoricApiFeedSource))
        ))
      }
    }
  }
}
