package controllers


import akka.event.LoggingAdapter
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import drt.shared.ApiFlight

import scala.language.postfixOps
import scala.collection.mutable

//todo think about where we really want this flight state, one source of truth?
trait FlightState {
  def log: LoggingAdapter

  var flights = Map[Int, ApiFlight]()
  val domesticPorts = Seq(
    "ABB","ABZ","ACI","ADV","ADX","AYH",
    "BBP","BBS","BEB","BEQ","BEX","BFS","BHD","BHX","BLK","BLY","BOH","BOL","BQH","BRF","BRR","BRS","BSH","BUT","BWF","BWY","BYT","BZZ",
    "CAL","CAX","CBG","CEG","CFN","CHE","CLB","COL","CRN","CSA","CVT","CWL",
    "DCS","DGX","DND","DOC","DSA","DUB",
    "EDI","EMA","ENK","EOI","ESH","EWY","EXT",
    "FAB","FEA","FFD","FIE","FKH","FLH","FOA","FSS","FWM","FZO",
    "GCI","GLA","GLO","GQJ","GSY","GWY","GXH",
    "HAW","HEN","HLY","HOY","HRT","HTF","HUY","HYC",
    "IIA","ILY","INQ","INV","IOM","IOR","IPW","ISC",
    "JER",
    "KIR","KKY","KNF","KOI","KRH","KYN",
    "LBA","LCY","LDY","LEQ","LGW","LHR","LKZ","LMO","LON","LPH","LPL","LSI","LTN","LTR","LWK","LYE","LYM","LYX",
    "MAN","MHZ","MME","MSE",
    "NCL","NDY","NHT","NNR","NOC","NQT","NQY","NRL","NWI",
    "OBN","ODH","OHP","OKH","ORK","ORM","OUK","OXF",
    "PIK","PLH","PME","PPW","PSL","PSV","PZE",
    "QCY","QFO","QLA","QUG",
    "RAY","RCS",
    "SCS","SDZ","SEN","SKL","SNN","SOU","SOY","SQZ","STN","SWI","SWS","SXL","SYY","SZD",
    "TRE","TSO","TTK",
    "UHF","ULL","UNT","UPV",
    "WAT","WEM","WEX","WFD","WHS","WIC","WOB","WRY","WTN","WXF",
    "YEO"
  )

  def onFlightUpdates(newFlights: List[ApiFlight], since: String) = {
    logNewFlightInfo(flights, newFlights)

    val withNewFlights = addNewFlights(flights, newFlights)
    val withoutOldFlights = filterOutFlightsBeforeThreshold(withNewFlights, since)
    val withoutDomesticFlights = filterOutDomesticFlights(withoutOldFlights, domesticPorts)

    flights = withoutDomesticFlights
  }

  def addNewFlights(existingFlights: Map[Int, ApiFlight], newFlights: List[ApiFlight]) = {
    existingFlights ++ newFlights.map(newFlight => (newFlight.FlightID, newFlight))
  }

  def filterOutFlightsBeforeThreshold(flights: Map[Int, ApiFlight], since: String): Map[Int, ApiFlight] = {
    val totalFlightsBeforeFilter = flights.size
    val flightsWithOldDropped = flights.filter { case (key, flight) => flight.EstDT >= since || flight.SchDT >= since }
    val totalFlightsAfterFilter = flights.size
    log.info(s"Dropped ${totalFlightsBeforeFilter - totalFlightsAfterFilter} flights before $since")
    flightsWithOldDropped
  }

  def filterOutDomesticFlights(flights: Map[Int, ApiFlight], domesticPorts: Seq[String]) = {
    flights.filter(flight => !domesticPorts.contains(flight._2.Origin))
  }

  def logNewFlightInfo(currentFlights: Map[Int, ApiFlight], newOrUpdatingFlights: List[ApiFlight]) = {
    val inboundFlightIds: Set[Int] = newOrUpdatingFlights.map(_.FlightID).toSet
    val existingFlightIds: Set[Int] = currentFlights.keys.toSet

    val updatingFlightIds = existingFlightIds intersect inboundFlightIds
    val newFlightIds = existingFlightIds diff inboundFlightIds
    if (newOrUpdatingFlights.nonEmpty) {
      log.debug(s"New flights ${newOrUpdatingFlights.filter(newFlightIds contains _.FlightID)}")
      log.debug(s"Old      fl ${currentFlights.filterKeys(updatingFlightIds).values}")
      log.debug(s"Updating fl ${newOrUpdatingFlights.filter(updatingFlightIds contains _.FlightID)}")
    }
    currentFlights
  }
}
