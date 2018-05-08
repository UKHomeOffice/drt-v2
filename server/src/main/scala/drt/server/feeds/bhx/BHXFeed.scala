package drt.server.feeds.bhx

import drt.shared.Arrival
import uk.co.bhx.online.flightinformation.FlightInformationSoap
import scala.collection.JavaConversions._
import scala.language.postfixOps

case class BHXFeed(serviceSoap: FlightInformationSoap) extends BHXLiveArrivals with BHXForecastArrivals {

  def getLiveArrivals: List[Arrival] = {
    val flightRecords = serviceSoap.bfGetFlights.getFlightRecord.toList
    flightRecords.map(toLiveArrival)
  }

  def getForecastArrivals: List[Arrival] = {
    val flights = serviceSoap.bfGetScheduledFlights().getScheduledFlightRecord.toList
    flights.map(toForecastArrival)
  }

}