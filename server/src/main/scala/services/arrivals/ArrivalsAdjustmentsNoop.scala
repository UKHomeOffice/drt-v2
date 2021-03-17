package services.arrivals

import drt.shared.{ArrivalsDiff, UniqueArrivalWithOrigin}

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivalsDiff: ArrivalsDiff, arrivalsKeys: Iterable[UniqueArrivalWithOrigin]): ArrivalsDiff = arrivalsDiff
}
