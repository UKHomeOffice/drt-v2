package services.arrivals

import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.redlist.RedListUpdates

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivals: Iterable[Arrival]): Iterable[Arrival] = arrivals
}
