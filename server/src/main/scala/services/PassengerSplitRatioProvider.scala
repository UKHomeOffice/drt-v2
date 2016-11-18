package services
import services.workloadcalculator.SplitRatio
import spatutorial.shared.ApiFlight

/**
  * Created by rich on 18/11/16.
  */
trait PassengerSplitRatioProvider {

  def splitRatioProvider(flight: ApiFlight): List[SplitRatio]
}
