package drt.shared

object CodeShares {
  def uniqueArrivalsWithCodeShares[GenFlight](apiFlightFromGenFlight: (GenFlight) => Arrival)
                                             (flights: Seq[GenFlight]): List[(GenFlight, Set[Arrival])] = {
    val grouped = flights.groupBy(f => {
      val arrival = apiFlightFromGenFlight(f)
      (arrival.Scheduled, arrival.Terminal, arrival.Origin)
    })
    grouped.values.flatMap(flights => {
      def mainFlightWithNoApiData: GenFlight = flights.sortBy(f => apiFlightFromGenFlight(f).ActPax).reverse.head
      val mainFlightsWithApiData = flights.filter(apiFlightFromGenFlight(_).FeedSources.contains(ApiFeed))
      val mainFlights: Seq[GenFlight] = if (mainFlightsWithApiData.isEmpty) Seq(mainFlightWithNoApiData) else mainFlightsWithApiData
      val shares: Set[Arrival] = flights
        .filterNot(mainFlights.contains)
        .toSet
        .map(apiFlightFromGenFlight)

      mainFlights.zipWithIndex.map { case (mf, i) => (mf, if (i == 0) shares else Set.empty[Arrival])

      }
    }).toList
  }
}
