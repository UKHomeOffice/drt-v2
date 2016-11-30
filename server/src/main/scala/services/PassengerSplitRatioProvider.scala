package services

import spatutorial.shared.{ApiFlight, SplitRatio}

trait PassengerSplitRatioProvider {

  def splitRatioProvider(flight: ApiFlight): List[SplitRatio]
}
