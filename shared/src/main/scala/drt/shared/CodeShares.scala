package drt.shared

import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.{PortCode, Terminals}

object CodeShares {
  def uniqueArrivalsWithCodeShares[GenFlight](apiFlightFromGenFlight: (GenFlight) => Arrival)
                                             (flights: Seq[GenFlight]): List[(GenFlight, Set[Arrival])] = {
    val grouped: Map[(Long, Terminals.Terminal, PortCode), Seq[GenFlight]] = flights.groupBy(f =>
      (apiFlightFromGenFlight(f).Scheduled, apiFlightFromGenFlight(f).Terminal, apiFlightFromGenFlight(f).Origin)
    )
    grouped.values.map(flights => {
      val mainFlight: GenFlight = flights.sortBy(f => apiFlightFromGenFlight(f).TotalPax.values.map(_.actual)).reverse.head
      val shares: Set[Arrival] = flights
        .filter(_ != mainFlight)
        .toSet
        .map(apiFlightFromGenFlight)

      (mainFlight, shares)
    }).toList
  }
}
