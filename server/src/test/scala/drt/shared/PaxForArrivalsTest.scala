package drt.shared

import drt.shared.FlightsApi.PaxForArrivals
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals.{Passengers, TotalPaxSource}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource, HistoricApiFeedSource, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.T1

class PaxForArrivalsTest extends Specification {
  "When I ask to extract the historic api nos" >> {
    "Given an arrival with no passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", sch = 0L, terminal = T1, origin = PortCode("ABC"), totalPax = Map())
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals.empty
      }
    }

    "Given an arrival with live api passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", sch = 0L, terminal = T1, origin = PortCode("ABC"),
        totalPax = Map(ApiFeedSource -> Passengers(Option(100), None)))
      "I should get an empty PaxForArrivals" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals.empty
      }
    }

    "Given an arrival with live & historic api passenger totals" >> {
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", sch = 0L, terminal = T1, origin = PortCode("ABC"),
        totalPax = Map(ApiFeedSource -> Passengers(Option(100), None), HistoricApiFeedSource -> Passengers(Option(100), None)))
      "I should get a PaxForArrivals with HistoricApiFeedSource with 100 passengers" >> {
        PaxForArrivals.from(Seq(arrival), HistoricApiFeedSource) === PaxForArrivals(Map(
          arrival.unique -> Map[FeedSource, Passengers](HistoricApiFeedSource -> Passengers(Option(100), None))
        ))
      }
    }
  }
}
