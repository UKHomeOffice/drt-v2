package drt.shared

object CodeShares {
  def uniqueArrivalsWithCodeshares(flights: Seq[ApiFlight]): List[(ApiFlight, Set[ApiFlight])] = {
    val grouped: Map[(String, String, String), Seq[ApiFlight]] = flights.groupBy(f => (f.SchDT, f.Terminal, f.Origin))
    grouped.map(gr => {
      val mainFlight: ApiFlight = gr._2.sortBy(_.ActPax).reverse.head
      val shares: Set[ApiFlight] = gr._2.filter(_ != mainFlight).toSet
      (mainFlight, shares)
    }).toList
  }

  def uniqueArrivalsWithSplitsAndCodeshares(flights: Seq[ApiFlightWithSplits]): List[(ApiFlightWithSplits, Set[ApiFlight])] = {
    val grouped: Map[(String, String, String), Seq[ApiFlightWithSplits]] = flights.groupBy(f => (f.apiFlight.SchDT, f.apiFlight.Terminal, f.apiFlight.Origin))
    grouped.map((gr: ((String, String, String), Seq[ApiFlightWithSplits])) => {
      val mainFlight: ApiFlightWithSplits = gr._2.sortBy(_.apiFlight.ActPax).reverse.head
      val shares: Set[ApiFlight] = gr._2.filter(_ != mainFlight).toSet.map((fws: ApiFlightWithSplits) => fws.apiFlight)
      (mainFlight, shares)
    }).toList
  }
}
