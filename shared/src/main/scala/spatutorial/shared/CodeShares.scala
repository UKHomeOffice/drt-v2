package drt.shared

object CodeShares {
  def uniqueArrivalsWithCodeshares[GenFlight](apiFlight: (GenFlight) => ApiFlight)(flights: Seq[GenFlight]): List[(GenFlight, Set[ApiFlight])] = {
    val grouped: Map[(String, String, String), Seq[GenFlight]] = flights.groupBy(f => (apiFlight(f).SchDT, apiFlight(f).Terminal, apiFlight(f).Origin))
    grouped.map {
      case (_, flights) =>
        val mainFlight: GenFlight = flights.sortBy(f => apiFlight(f).ActPax).reverse.head
        val shares: Set[ApiFlight] = flights
          .filter(_ != mainFlight)
          .toSet
          .map(apiFlight)

        (mainFlight, shares)
    }.toList
  }
}
