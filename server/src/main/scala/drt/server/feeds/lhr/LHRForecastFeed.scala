package drt.server.feeds.lhr


import drt.server.feeds.Implicits._
import drt.server.feeds.lhr.forecast.LHRForecastFlightRow
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{Arrival, Passengers, Predictions}
import uk.gov.homeoffice.drt.ports.ForecastFeedSource
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.util.Try


object LHRForecastFeed {
  def log: Logger = LoggerFactory.getLogger(getClass)

  def lhrFieldsToArrival(flightRow: LHRForecastFlightRow): Try[Arrival] = {
    val actualPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax)
    val transitPax = if (flightRow.transferPax == 0) None else Option(flightRow.transferPax)
    Try {
      Arrival(
        Operator = None,
        Status = "Port Forecast",
        Estimated = None,
        Predictions = Predictions(0L, Map()),
        Actual = None,
        EstimatedChox = None,
        ActualChox = None,
        Gate = None,
        Stand = None,
        MaxPax = None,
        RunwayID = None,
        BaggageReclaimId = None,
        AirportID = "LHR",
        Terminal = Terminal(flightRow.terminal),
        rawICAO = flightRow.flightCode.replace(" ", ""),
        rawIATA = flightRow.flightCode.replace(" ", ""),
        Origin = flightRow.origin,
        Scheduled = flightRow.scheduledDate.millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeedSource),
        PassengerSources = Map(ForecastFeedSource -> Passengers(actualPax, transitPax))
      )
    }
  }
}
