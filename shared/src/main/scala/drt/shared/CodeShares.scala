package drt.shared

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages

object CodeShares {
  def uniqueArrivals[GenFlight](apiFlightFromGenFlight: GenFlight => Arrival,
                                paxFeedSourceOrder: List[FeedSource],
                               )
                               (flights: Seq[GenFlight]): Iterable[GenFlight] =
    uniqueArrivalsWithCodeShares(apiFlightFromGenFlight, paxFeedSourceOrder)(flights).map(_._1)

  def uniqueArrivalsWithCodeShares[GenFlight](apiFlightFromGenFlight: GenFlight => Arrival,
                                              paxFeedSourceOrder: List[FeedSource],
                                             )
                                             (flights: Seq[GenFlight]): Seq[(GenFlight, Seq[String])] = {
    flights
      .groupBy(f =>
        (apiFlightFromGenFlight(f).Scheduled, apiFlightFromGenFlight(f).Terminal, apiFlightFromGenFlight(f).Origin)
      )
      .values
      .map { flights =>
        val mainFlight = flights
          .sortBy { f =>
            val apiScore = if (apiFlightFromGenFlight(f).PassengerSources.contains(ApiFeedSource)) 10000 else 0
            val paxScore = apiFlightFromGenFlight(f).bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0)
            apiScore + paxScore
          }
          .reverse.head
        val shares = flights.filter(_ != mainFlight).map(f => apiFlightFromGenFlight(f).flightCodeString)

        (mainFlight, shares)
      }
      .toSeq
      .sortBy {
        case (mainFlight, _) =>
          val pcpTime = apiFlightFromGenFlight(mainFlight).PcpTime.getOrElse(0L)
          (pcpTime, apiFlightFromGenFlight(mainFlight).VoyageNumber.numeric)
      }
  }
}
