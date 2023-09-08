package drt.shared

import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.FeedSource

import scala.util.{Failure, Success, Try}

object CodeShares {
  def uniqueArrivals[GenFlight](apiFlightFromGenFlight: GenFlight => Arrival,
                                paxFeedSourceOrder: List[FeedSource],
                               )
                               (flights: Seq[GenFlight]): Iterable[GenFlight] =
    uniqueArrivalsWithCodeShares(apiFlightFromGenFlight, paxFeedSourceOrder)(flights).map(_._1)

  def uniqueArrivalsWithCodeShares[GenFlight](apiFlightFromGenFlight: GenFlight => Arrival,
                                              paxFeedSourceOrder: List[FeedSource],
                                             )
                                             (flights: Seq[GenFlight]): Seq[(GenFlight, Seq[Arrival])] = {
    flights
      .groupBy(f =>
        (apiFlightFromGenFlight(f).Scheduled, apiFlightFromGenFlight(f).Terminal, apiFlightFromGenFlight(f).Origin)
      )
      .values
      .map { flights =>
        val mainFlight = flights
          .sortBy(f => apiFlightFromGenFlight(f).bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0))
          .reverse.head
        val shares = flights
          .filter(_ != mainFlight)
          .map(apiFlightFromGenFlight)
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
