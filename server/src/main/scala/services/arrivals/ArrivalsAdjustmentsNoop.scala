package services.arrivals

import uk.gov.homeoffice.drt.arrivals.Arrival

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def adjust(arrival: Arrival): Arrival = arrival
}
