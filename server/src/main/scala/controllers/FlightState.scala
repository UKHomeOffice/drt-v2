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

  def onFlightUpdates(newFlights: List[ApiFlight], since: String) = {

    val loggedFlights = logNewFlightInfo(flights, newFlights)
    val withNewFlights = addNewFlights(loggedFlights, newFlights)
    val withoutOldFlights = filterOutFlightsBeforeThreshold(withNewFlights, since)
    flights = withoutOldFlights
  }

  def addNewFlights(flights: Map[Int, ApiFlight], fs: List[ApiFlight]) = {
    val newFlights: List[(Int, ApiFlight)] = fs.map(f => (f.FlightID, f))
    flights ++ newFlights
  }

  def filterOutFlightsBeforeThreshold(flights: Map[Int, ApiFlight], since: String): Map[Int, ApiFlight] = {
    val totalFlightsBeforeFilter = flights.size
    val flightsWithOldDropped = flights.filter { case (key, flight) => flight.EstDT >= since || flight.SchDT >= since }
    val totalFlightsAfterFilter = flights.size
    log.info(s"Dropped ${totalFlightsBeforeFilter - totalFlightsAfterFilter} flights before $since")
    flightsWithOldDropped
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
