package services.arrivals

import drt.shared.api.Arrival

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivals: Iterable[Arrival]): Iterable[Arrival] = arrivals
}
