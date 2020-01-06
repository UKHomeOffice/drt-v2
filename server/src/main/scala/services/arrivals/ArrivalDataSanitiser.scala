package services.arrivals

import drt.shared.Arrival

case class ArrivalDataSanitiser(
                                 maybeEstimatedThresholdHours: Option[Int],
                                 maybeTaxiThresholdMinutes: Option[Int]) {

  def maybeEstThresholdMillis: Option[Long] = maybeEstimatedThresholdHours.map(h => h * 60 * 60 * 1000L)

  def maybeTaxiThresholdMillis: Option[Long] = maybeTaxiThresholdMinutes.map(m => m * 60 * 1000L)

  def withSaneEstimates(arrival: Arrival): Arrival = withSaneEstimatedChox(withSaneEstimatedTouchDown(arrival))

  def withSaneEstimatedTouchDown(arrival: Arrival): Arrival =
    (maybeEstThresholdMillis, arrival.Estimated, arrival.Actual) match {
      case (Some(threshold), Some(est), _) if (Math.abs(est - arrival.Scheduled) > threshold) =>
        arrival.copy(Estimated = None)
      case (_, Some(est), Some(act)) if est != act =>
        arrival.copy(Estimated = arrival.Actual)
      case _ => arrival
    }

  def withSaneEstimatedChox(arrival: Arrival): Arrival =
    (maybeTaxiThresholdMillis, maybeEstThresholdMillis, arrival.EstimatedChox, arrival.Estimated) match {
      case (_, Some(threshold), Some(est), _) if (Math.abs(est - arrival.Scheduled) > threshold) =>
        arrival.copy(EstimatedChox = None)
      case (_, _, Some(estChox), Some(est)) if estChox <= est =>
        arrival.copy(EstimatedChox = None)
      case (Some(taxiThreshold), _, Some(estChox), Some(est)) if est + taxiThreshold < estChox =>
        arrival.copy(EstimatedChox = None)
      case _ => arrival
    }
}

object ArrivalDataSanitiser {
  val arrivalDataSanitiserWithoutThresholds = ArrivalDataSanitiser(None, None)
}
