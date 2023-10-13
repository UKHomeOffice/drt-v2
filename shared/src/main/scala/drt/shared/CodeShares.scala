package drt.shared

import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.FeedSource

object CodeShares {
  def uniqueArrivals[GenFlight](apiFlightFromGenFlight: GenFlight => Arrival,
                                paxFeedSourceOrder: List[FeedSource],
                               )
                               (flights: Seq[GenFlight]): Iterable[GenFlight] =
    uniqueArrivalsWithCodeShares(apiFlightFromGenFlight, paxFeedSourceOrder)(flights).map(_._1)

  def uniqueArrivalsWithCodeShares[GenFlight](apiFlightFromGenFlight: GenFlight => Arrival,
                                              paxFeedSourceOrder: List[FeedSource],
                                             )
                                             (flights: Seq[GenFlight]): Seq[(GenFlight, Seq[GenFlight])] = {
    flights
      .groupBy(f =>
        (apiFlightFromGenFlight(f).Scheduled, apiFlightFromGenFlight(f).Terminal, apiFlightFromGenFlight(f).Origin)
      )
      .values
      .map { flights =>
        val mainFlight = flights
          .sortBy(f => apiFlightFromGenFlight(f).bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0))
          .reverse.head
        val shares = flights.filter(_ != mainFlight)

        (mainFlight, shares)
      }
      .toSeq
      .sortBy {
        case (mainFlight, _) =>
          val pcpTime = apiFlightFromGenFlight(mainFlight).PcpTime.getOrElse(0L)
          (pcpTime, apiFlightFromGenFlight(mainFlight).VoyageNumber.numeric)
      }
  }

  def retainSplitsAndCodeShares(uniqueWithCodeShares: Seq[(ApiFlightWithSplits, Seq[ApiFlightWithSplits])]): Seq[(ApiFlightWithSplits, Seq[String])] =
    uniqueWithCodeShares.map {
      case (flight, codeShares) =>
        val withSplits = if (flight.splits.isEmpty)
          codeShares.find(_.splits.nonEmpty).map(f => flight.copy(splits = f.splits)).getOrElse(flight)
        else flight
        val codeShareFlightNumbers = codeShares.map(_.apiFlight.flightCodeString)
        
        (withSplits, codeShareFlightNumbers)
    }

  def retainSplits(uniqueWithCodeShares: Seq[(ApiFlightWithSplits, Seq[ApiFlightWithSplits])]): Seq[ApiFlightWithSplits] =
    retainSplitsAndCodeShares(uniqueWithCodeShares).map(_._1)
}
