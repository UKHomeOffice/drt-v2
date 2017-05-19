package drt.shared

object CodeShares {
  def uniqueArrivalsWithCodeshares[GenFlight](toFlight: (GenFlight) => ApiFlight)(flights: Seq[GenFlight]): List[(GenFlight, Set[ApiFlight])] = {
    val grouped: Map[(String, String, String), Seq[GenFlight]] = flights.groupBy(f => (toFlight(f).SchDT, toFlight(f).Terminal, toFlight(f).Origin))
    grouped.map {
      case (_, flights) =>
        val mainFlight: GenFlight = flights.sortBy(f => toFlight(f).ActPax).reverse.head
        val shares: Set[ApiFlight] = flights
          .filter(_ != mainFlight)
          .toSet
          .map(toFlight)

        (mainFlight, shares)
    }.toList
  }
}
