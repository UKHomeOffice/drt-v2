package services.exports

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone


object Exports {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def millisToLocalIsoDateOnly: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate.millisToLocalIsoDateOnly(europeLondonTimeZone)(millis)

  def millisToLocalDateTimeString: MillisSinceEpoch => String = (millis: MillisSinceEpoch) => SDate(millis, europeLondonTimeZone).toLocalDateTimeString

  def actualAPISplitsAndHeadingsFromFlight(flightWithSplits: ApiFlightWithSplits): Set[(String, Double)] = flightWithSplits
    .splits
    .collect {
      case s: Splits if s.source == SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages =>
        s.splits.map(s => (s"API Actual - ${s.paxTypeAndQueue.displayName}", s.paxCount))
    }
    .flatten
}
