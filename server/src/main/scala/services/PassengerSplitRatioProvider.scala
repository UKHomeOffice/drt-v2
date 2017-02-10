package services

import spatutorial.shared.ApiFlight
import spatutorial.shared.SplitRatios.SplitRatio

trait PassengerSplitRatioProvider {
  def splitRatioProvider: (ApiFlight) => Option[List[SplitRatio]]
}
