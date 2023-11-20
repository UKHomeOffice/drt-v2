package services.arrivals

import uk.gov.homeoffice.drt.arrivals.Arrival

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivals: Iterable[Arrival]): Iterable[Arrival] = arrivals
}
