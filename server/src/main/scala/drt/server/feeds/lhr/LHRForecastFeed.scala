package drt.server.feeds.lhr

import drt.server.feeds.lhr.forecast.{LHRForecastEmail, LHRForecastFlightRow, LHRForecastXLSExtractor}
import drt.shared.{Arrival, ForecastFeed}
import drt.shared.FlightsApi.Flights
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}
import services.SDate

import scala.util.{Success, Try}

case class LHRForecastFeed(
                            mailHost: String,
                            userName: String,
                            userPassword: String,
                            from: String,
                            mailPort: Int = 993
                          ) {

  def requestFeed: ArrivalsFeedResponse = {
    LHRForecastEmail(mailHost, userName, userPassword, from, mailPort).maybeLatestForecastFile match {
      case Some(xlsForecastDoc) =>
        val arrivals = LHRForecastXLSExtractor(xlsForecastDoc.getPath)
          .map(LHRForecastFeed.lhrFieldsToArrival)
          .collect {
            case Success(arrival) => arrival
          }
        if (arrivals.nonEmpty) ArrivalsFeedSuccess(Flights(arrivals), SDate.now())
        else ArrivalsFeedFailure("No forecast arrivals found", SDate.now())
      case None => ArrivalsFeedFailure("No forecast mail attachment found", SDate.now())
    }
  }

}

object LHRForecastFeed {
  def log: Logger = LoggerFactory.getLogger(classOf[LHRForecastFeed])

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
        FlightID = None,
        AirportID = "LHR",
        Terminal = flightRow.terminal,
        rawICAO = flightRow.flightCode.replace(" ", ""),
        rawIATA = flightRow.flightCode.replace(" ", ""),
        Origin = flightRow.origin,
        Scheduled = flightRow.scheduledDate.millisSinceEpoch,
        PcpTime = None,
        FeedSources = Set(ForecastFeed),
        None
      )
    }
  }
}
