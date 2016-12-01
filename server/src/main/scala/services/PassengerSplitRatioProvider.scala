package services

import spatutorial.shared.{ApiFlight, SplitRatio}

trait PassengerSplitRatioProvider {
  def splitRatioProvider: (ApiFlight) => Option[List[SplitRatio]]
}
