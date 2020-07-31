package services.arrivals

import drt.shared.{ArrivalsDiff, UniqueArrival}

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivalsDiff: ArrivalsDiff, arrivalsKeys: Iterable[UniqueArrival]): ArrivalsDiff = arrivalsDiff
}
