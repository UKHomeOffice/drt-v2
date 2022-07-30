package drt.server.feeds.legacy.bhx

import uk.co.bhx.online.flightinformation.FlightInformationSoap
import uk.gov.homeoffice.drt.arrivals.Arrival

import scala.collection.JavaConverters._

case class BHXFeed(serviceSoap: FlightInformationSoap) extends BHXLiveArrivals with BHXForecastArrivals {

  def getLiveArrivals: List[Arrival] = {
    val flightRecords = serviceSoap.bfGetFlights.getFlightRecord.asScala
    flightRecords.map(toLiveArrival).toList
  }

  def getForecastArrivals: List[Arrival] = {
    val flights = serviceSoap.bfGetScheduledFlights().getScheduledFlightRecord.asScala
    flights.map(toForecastArrival).toList
  }

}
