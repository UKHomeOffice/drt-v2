package drt.server.feeds.lhr


import drt.server.feeds.Implicits._
import drt.server.feeds.lhr.forecast.LHRForecastFlightRow
import drt.shared.ForecastFeedSource
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import org.slf4j.{Logger, LoggerFactory}

import scala.language.postfixOps
import scala.util.Try


object LHRForecastFeed {
  def log: Logger = LoggerFactory.getLogger(getClass)

  def lhrFieldsToArrival(flightRow: LHRForecastFlightRow): Try[Arrival] = {
    Try {
      Arrival(
        Operator = None,
        Status = "Port Forecast",
        Estimated = None,
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = None,
        ActPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax),
        TranPax = if (flightRow.totalPax == 0) None else Option(flightRow.transferPax),
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = "LHR",
        Terminal = Terminal(flightRow.terminal),
        rawICAO = flightRow.flightCode.replace(" ", ""),
        rawIATA = flightRow.flightCode.replace(" ", ""),
        Origin = flightRow.origin,
        Scheduled = flightRow.scheduledDate.millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource)
      )
    }
  }
}
