package services
import services.workloadcalculator.SplitRatio
import spatutorial.shared.ApiFlight

trait PassengerSplitRatioProvider {

  def splitRatioProvider(flight: ApiFlight): List[SplitRatio]
}
