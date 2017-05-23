package drt.shared

object CodeShares {
  def uniqueArrivalsWithCodeshares[GenFlight](apiFlightFromGenFlight: (GenFlight) => ApiFlight)(flights: Seq[GenFlight]): List[(GenFlight, Set[ApiFlight])] = {
    val grouped: Map[(String, String, String), Seq[GenFlight]] = flights.groupBy(f => (apiFlightFromGenFlight(f).SchDT, apiFlightFromGenFlight(f).Terminal, apiFlightFromGenFlight(f).Origin))
    grouped.values.map(flights => {
        val mainFlight: GenFlight = flights.sortBy(f => apiFlightFromGenFlight(f).ActPax).reverse.head
        val shares: Set[ApiFlight] = flights
          .filter(_ != mainFlight)
          .toSet
          .map(apiFlightFromGenFlight)

        (mainFlight, shares)
    }).toList
  }
}
