package services

import drt.shared.ApiFlight
import drt.shared.SplitRatiosNs.SplitRatios

trait PassengerSplitRatioProvider {
  def splitRatioProvider: (ApiFlight) => Option[SplitRatios]
}
