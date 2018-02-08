package drt.server.feeds.lhr

import drt.server.feeds.lhr.forecast.{LHRForecastEmail, LHRForecastFlightRow, LHRForecastXLSExtractor}
import drt.shared.Arrival
import org.slf4j.LoggerFactory
import services.SDate

import scala.util.{Success, Try}

case class LHRForecastFeed(
                            mailHost: String,
                            userName: String,
                            userPassword: String,
                            from: String,
                            mailPort: Int = 993
                          ) {

  def arrivals: List[Arrival] = {
    val xlsForecastDoc = LHRForecastEmail(mailHost, userName, userPassword, from, mailPort).maybeLatestForecastFile
    xlsForecastDoc.map(xl =>
      {

        LHRForecastXLSExtractor(xl.getPath).map(LHRForecastFeed.lhrFieldsToArrival).collect {
          case Success(arrival) => arrival
        }
      }).getOrElse(List())
  }

}

object LHRForecastFeed {

  def log = LoggerFactory.getLogger(classOf[LHRForecastFeed])


  def lhrFieldsToArrival(flightRow: LHRForecastFlightRow): Try[Arrival] = {
    Try {
      Arrival(
        Operator = "",
        Status = "Port Forecast",
        EstDT = "",
        ActDT = "",
        EstChoxDT = "",
        ActChoxDT = "",
        Gate = "",
        Stand = "",
        MaxPax = 0,
        ActPax = flightRow.totalPax,
        TranPax = flightRow.transferPax,
        RunwayID = "",
        BaggageReclaimId = "",
        FlightID = 0,
        AirportID = "LHR",
        Terminal = flightRow.terminal,
        rawICAO = flightRow.flightCode.replace(" ", ""),
        rawIATA = flightRow.flightCode.replace(" ", ""),
        Origin = flightRow.origin,
        SchDT = flightRow.scheduledDate.toISOString(),
        Scheduled = flightRow.scheduledDate.millisSinceEpoch,
        PcpTime = 0,
        None
      )
    }
  }
}
