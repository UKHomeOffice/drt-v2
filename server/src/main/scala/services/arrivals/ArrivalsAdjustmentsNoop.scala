package services.arrivals

import drt.shared.api.Arrival
import uk.gov.homeoffice.drt.redlist.RedListUpdates

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivals: Iterable[Arrival], redListUpdates: RedListUpdates): Iterable[Arrival] = arrivals
}
