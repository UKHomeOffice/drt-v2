package services.arrivals
import drt.shared.ArrivalsDiff

object ArrivalsAdjustmentsNoop extends ArrivalsAdjustmentsLike {
  override def apply(arrivalsDiff: ArrivalsDiff): ArrivalsDiff = arrivalsDiff
}
