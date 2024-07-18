package drt.shared

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}

object CodeShares {

  def uniqueArrivals(paxFeedSourceOrder: List[FeedSource])
                    (flights: Seq[ApiFlightWithSplits]): Iterable[ApiFlightWithSplits] =
    uniqueArrivalsWithCodeShares(paxFeedSourceOrder)(flights, (f: ApiFlightWithSplits) => f.hasApi, (f: ApiFlightWithSplits) => f.apiFlight).map(_._1)

  def uniqueArrivalsWithCodeShares[T](paxFeedSourceOrder: List[FeedSource])
                                     (flights: Seq[T], hasApi: T => Boolean, arrival: T => Arrival): Seq[(T, Seq[String])] = {
    flights
      .groupBy(f =>
        (arrival(f).Scheduled, arrival(f).Terminal, arrival(f).Origin)
      )
      .values
      .map { flights =>
        val mainFlight = flights
          .sortBy { f =>
            val apiScore = if (arrival(f).PassengerSources.contains(ApiFeedSource) || hasApi(f)) 10000 else 0
            val paxScore = arrival(f).bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0)
            apiScore + paxScore
          }
          .reverse.head
        val shares = flights.filter(_ != mainFlight).map(f => arrival(f).flightCodeString)

        (mainFlight, shares)
      }
      .toSeq
      .sortBy {
        case (mainFlight, _) =>
          val pcpTime = arrival(mainFlight).PcpTime.getOrElse(0L)
          (pcpTime, arrival(mainFlight).VoyageNumber.numeric)
      }
  }

  def uniqueFlightsWithCodeShares(paxFeedSourceOrder: List[FeedSource])(flights: Seq[ApiFlightWithSplits]): Seq[(ApiFlightWithSplits, Seq[String])] =
    uniqueArrivalsWithCodeShares(paxFeedSourceOrder)(flights, (f: ApiFlightWithSplits) => f.hasApi, (f: ApiFlightWithSplits) => f.apiFlight)
}
