package controllers


import akka.event.LoggingAdapter
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import spatutorial.shared.ApiFlight

import scala.language.postfixOps
import scala.collection.mutable

//todo think about where we really want this flight state, one source of truth?
trait FlightState {
  def log: LoggingAdapter

  var flights = Map[Int, ApiFlight]()

  def onFlightUpdates(fs: List[ApiFlight], since: String) = {
    val currentFlights = flights

    val loggedFlights = logNewFlightInfo(flights, fs)
    val withNewFlights = addNewFlights(loggedFlights, fs)
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

  def logNewFlightInfo(flights: Map[Int, ApiFlight], fs: List[ApiFlight]) = {
    log.info(s"Flights before change: $flights")

    val inboundFlightIds: Set[Int] = fs.map(_.FlightID).toSet
    val existingFlightIds: Set[Int] = flights.keys.toSet

    val updatingFlightIds = existingFlightIds intersect inboundFlightIds
    val newFlightIds = existingFlightIds diff inboundFlightIds

    log.info(s"New flights ${fs.filter(newFlightIds contains _.FlightID)}")
    log.info(s"Old      fl ${flights.filterKeys(updatingFlightIds).values}")
    log.info(s"Updating fl ${fs.filter(updatingFlightIds contains _.FlightID)}")
    flights
  }
}
