package drt.server.feeds.lhr


import drt.server.feeds.lhr.forecast.LHRForecastFlightRow
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.arrivals.{FlightCode, ForecastArrival}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.util.Try


object LHRForecastFeed {
  def log: Logger = LoggerFactory.getLogger(getClass)

  def lhrFieldsToArrival(flightRow: LHRForecastFlightRow): Try[ForecastArrival] = {
    val totalPax = if (flightRow.totalPax == 0) None else Option(flightRow.totalPax)
    val transPax = if (flightRow.transferPax == 0) None else Option(flightRow.transferPax)
    val (carrierCode, voyageNumber, suffix) = FlightCode.flightCodeToParts(flightRow.flightCode.replace(" ", ""))
    Try {
      ForecastArrival(
        operator = None,
        maxPax = None,
        totalPax = totalPax,
        transPax = transPax,
        terminal = Terminal(flightRow.terminal),
        voyageNumber = voyageNumber.numeric,
        carrierCode = carrierCode.code,
        flightCodeSuffix = suffix.map(_.suffix),
        origin = flightRow.origin,
        scheduled = flightRow.scheduledDate.millisSinceEpoch,
      )
    }
  }
}
