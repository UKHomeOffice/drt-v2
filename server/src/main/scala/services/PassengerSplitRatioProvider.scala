package services

import drt.shared.Arrival
import drt.shared.SplitRatiosNs.SplitRatios

trait PassengerSplitRatioProvider {
  def splitRatioProvider: (Arrival) => Option[SplitRatios]
}
