package drt.shared

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}

object CodeShares {

  def uniqueArrivals(paxFeedSourceOrder: List[FeedSource])
                    (flights: Seq[ApiFlightWithSplits]): Iterable[ApiFlightWithSplits] =
    uniqueArrivalsWithCodeShares(paxFeedSourceOrder)(flights).map(_._1)

  def uniqueArrivalsWithCodeShares(paxFeedSourceOrder: List[FeedSource])
                                  (flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Seq[String])] = {
    flights
      .groupBy(f =>
        (f.apiFlight.Scheduled, f.apiFlight.Terminal, f.apiFlight.Origin)
      )
      .values
      .map { flights =>
        val mainFlight = flights
          .sortBy { f =>
            val apiScore = if (f.apiFlight.PassengerSources.contains(ApiFeedSource) || f.hasApi) 10000 else 0
            val paxScore = f.apiFlight.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0)
            apiScore + paxScore
          }
          .reverse.head
        val shares = flights.filter(_ != mainFlight).map(f => f.apiFlight.flightCodeString)

        (mainFlight, shares)
      }
      .toSeq
      .sortBy {
        case (mainFlight, _) =>
          val pcpTime = mainFlight.apiFlight.PcpTime.getOrElse(0L)
          (pcpTime, mainFlight.apiFlight.VoyageNumber.numeric)
      }
  }
}
