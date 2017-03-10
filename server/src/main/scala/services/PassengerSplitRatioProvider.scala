package services

import spatutorial.shared.ApiFlight
import spatutorial.shared.SplitRatiosNs.SplitRatios

trait PassengerSplitRatioProvider {
  def splitRatioProvider: (ApiFlight) => Option[SplitRatios]
}
