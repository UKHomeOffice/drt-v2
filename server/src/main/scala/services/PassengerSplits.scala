package services

import com.typesafe.config.{Config, ConfigFactory}
import controllers.AirportConfProvider
import spatutorial.shared.{ApiFlight, SplitRatio}

object SplitsProvider {
  def splitsForFlight(providers: List[ApiFlight => Option[List[SplitRatio]]])(apiFlight: ApiFlight): Option[List[SplitRatio]] = {
    providers.foldLeft(None: Option[List[SplitRatio]])((prev, provider) => {
      prev match {
        case Some(split) => prev
        case None => provider(apiFlight)
      }
    })
  }
}

trait ProdSplitsProvider extends AirportConfProvider {
  def shouldUseCsvSplitsProvider: Boolean = {
    val config: Config = ConfigFactory.load

    config.hasPath("passenger_splits_csv_url") && config.getString("passenger_splits_csv_url") != ""
  }

  def emptyProvider: (ApiFlight => Option[List[SplitRatio]]) = _ => Option.empty[List[SplitRatio]]

  def csvProvider: (ApiFlight) => Option[List[SplitRatio]] = {
    if (shouldUseCsvSplitsProvider)
      new CSVPassengerSplitsProvider {
        override def flightPassengerSplitLines = CsvPassengerSplitsReader.flightPaxSplitsLinesFromConfig
      }.splitRatioProvider
    else
      emptyProvider
  }

  def defaultProvider: (ApiFlight) => Some[List[SplitRatio]] = {
    _ => Some(getPortConfFromEnvVar.defaultPaxSplits)
  }

  def splitRatioProvider = SplitsProvider.splitsForFlight(List(csvProvider, defaultProvider)) _
}