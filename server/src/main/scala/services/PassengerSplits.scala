package services

import com.typesafe.config.{Config, ConfigFactory}
import controllers.AirportConfProvider
import spatutorial.shared.SplitRatios.SplitRatios
import spatutorial.shared.{AirportConfig, ApiFlight}

object SplitsProvider {
  type SplitProvider = (ApiFlight) => Option[SplitRatios]

  def splitsForFlight(providers: List[SplitProvider])(apiFlight: ApiFlight): Option[SplitRatios] = {
    providers.foldLeft(None: Option[SplitRatios])((prev, provider) => {
      prev match {
        case Some(split) => prev
        case None => provider(apiFlight)
      }
    })
  }
  def shouldUseCsvSplitsProvider: Boolean = {
    val config: Config = ConfigFactory.load

    config.hasPath("passenger_splits_csv_url") && config.getString("passenger_splits_csv_url") != ""
  }

  def emptyProvider: SplitProvider = _ => Option.empty[SplitRatios]

  def csvProvider: (ApiFlight) => Option[SplitRatios] = {
    if (shouldUseCsvSplitsProvider)
      CSVPassengerSplitsProvider(CsvPassengerSplitsReader.flightPaxSplitsLinesFromConfig).splitRatioProvider
    else
      emptyProvider
  }

  def defaultProvider(airportConf: AirportConfig): (ApiFlight) => Some[SplitRatios] = {
    _ => Some(airportConf.defaultPaxSplits)
  }

}

