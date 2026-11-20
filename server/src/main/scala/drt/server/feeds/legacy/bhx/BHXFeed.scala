package drt.server.feeds.legacy.bhx

import uk.co.bhx.online.flightinformation.FlightInformationSoap
import uk.gov.homeoffice.drt.arrivals.LiveArrival

import scala.jdk.CollectionConverters.CollectionHasAsScala

case class BHXFeed(serviceSoap: FlightInformationSoap) extends BHXLiveArrivals {

  def getLiveArrivals: List[LiveArrival] = {
    val flightRecords = serviceSoap.bfGetFlights.getFlightRecord.asScala
    flightRecords.map(toLiveArrival).toList
  }
}
