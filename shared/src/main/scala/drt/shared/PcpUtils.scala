//package drt.shared
//
//import drt.shared.api.Arrival
//import uk.gov.homeoffice.drt.ports.LiveFeedSource
//
//object PcpUtils {
//  val defaultPax = 0
//
//  def bestPcpPaxEstimate(flight: Arrival): Int =
//    (flight.ApiPax, flight.ActPax, flight.TranPax, flight.MaxPax) match {
//      case (Some(apiPax), _, _, _) if !flight.FeedSources.contains(LiveFeedSource) => apiPax
//      case (_, Some(actPax), Some(tranPax), _) if (actPax - tranPax) >= 0 => actPax - tranPax
//      case (_, Some(actPax), None, _) => actPax
//      case (Some(apiPax), _, _, _) => apiPax
//      case _ => defaultPax
//    }
//
//  def walkTime(arrival: Arrival, timeToChox: Long, firstPaxOff: Long, considerPredictions: Boolean): Option[Long] =
//    arrival.PcpTime.map(pcpTime => pcpTime - (arrival.bestArrivalTime(timeToChox, considerPredictions) + firstPaxOff))
//}
