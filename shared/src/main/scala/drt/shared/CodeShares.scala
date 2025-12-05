package drt.shared

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightCode}
import uk.gov.homeoffice.drt.ports.{ApiFeedSource, FeedSource}

object CodeShares {
  private def uniqueArrivalsWithCodeShares[T](paxFeedSourceOrder: List[FeedSource], exceptions: Set[FlightCode])
                                             (flights: Seq[T], hasApi: T => Boolean, arrival: T => Arrival): Seq[(T, Seq[String])] = {
    flights
      .groupBy { f =>
        val code = arrival(f).flightCode
        val exceptionKey = Some(code).filter(exceptions.contains)
        (arrival(f).Scheduled, arrival(f).Terminal, arrival(f).Origin, exceptionKey)
      }
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

  def uniqueFlightsWithCodeShares(paxFeedSourceOrder: List[FeedSource],
                                  exceptions: Set[FlightCode],
                                 ): Seq[ApiFlightWithSplits] => Seq[(ApiFlightWithSplits, Seq[String])] = {
    val fn: (Seq[ApiFlightWithSplits], ApiFlightWithSplits => Boolean, ApiFlightWithSplits => Arrival) => Seq[(ApiFlightWithSplits, Seq[String])] =
      uniqueArrivalsWithCodeShares(paxFeedSourceOrder, exceptions)

    flights =>
      fn(flights, (f: ApiFlightWithSplits) => f.hasApi, (f: ApiFlightWithSplits) => f.apiFlight)
  }

  def uniqueArrivals(paxFeedSourceOrder: List[FeedSource],
                     exceptions: Set[FlightCode],
                    ): Seq[ApiFlightWithSplits] => Iterable[ApiFlightWithSplits] = {
    val fn = uniqueFlightsWithCodeShares(paxFeedSourceOrder, exceptions)

    flights =>
      fn(flights).map(_._1)
  }
}
