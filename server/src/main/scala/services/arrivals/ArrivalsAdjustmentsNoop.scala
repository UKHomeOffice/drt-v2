package services.arrivals

import drt.shared.api.Arrival
import drt.shared.redlist.RedListUpdates

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivals: Iterable[Arrival], redListUpdates: RedListUpdates): Iterable[Arrival] = arrivals
}
