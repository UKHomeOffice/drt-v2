package controllers


import akka.event.LoggingAdapter
import com.typesafe.config.ConfigFactory
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import drt.shared.{Arrival, MilliDate}
import services.PcpArrival.{pcpFrom, walkTimeMillisProviderFromCsv}

import scala.language.postfixOps
import scala.collection.mutable

//todo think about where we really want this flight state, one source of truth?
trait FlightState {
  def log: LoggingAdapter

  var flights = Map[Int, Arrival]()

  def onFlightUpdates(newFlights: List[Arrival], since: String, domesticPorts: Seq[String]) = {
    logNewFlightInfo(flights, newFlights)

    val withNewFlights = addNewFlights(flights, newFlights)
    val withoutOldFlights = filterOutFlightsBeforeThreshold(withNewFlights, since)
    val withoutDomesticFlights = filterOutDomesticFlights(withoutOldFlights, domesticPorts)

    setFlights(withoutDomesticFlights)
  }

  def state = flights

  def setFlights(withoutDomesticFlights: Map[Int, Arrival]) = {
    flights = withoutDomesticFlights
  }

  def addNewFlights(existingFlights: Map[Int, Arrival], newFlights: List[Arrival]) = {
    existingFlights ++ newFlights.map(newFlight => (newFlight.FlightID, newFlight))
  }

  def filterOutFlightsBeforeThreshold(flights: Map[Int, Arrival], since: String): Map[Int, Arrival] = {
    val totalFlightsBeforeFilter = flights.size
    val flightsWithOldDropped = flights.filter { case (key, flight) => flight.EstDT >= since || flight.SchDT >= since }
    val totalFlightsAfterFilter = flights.size
    log.info(s"Dropped ${totalFlightsBeforeFilter - totalFlightsAfterFilter} flights before $since")
    flightsWithOldDropped
  }

  def filterOutDomesticFlights(flights: Map[Int, Arrival], domesticPorts: Seq[String]) = {
    flights.filter(flight => !domesticPorts.contains(flight._2.Origin))
  }

  def logNewFlightInfo(currentFlights: Map[Int, Arrival], newOrUpdatingFlights: List[Arrival]) = {
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
