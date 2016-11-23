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

  val flights = mutable.Map[Int, ApiFlight]()

  def onFlightUpdates(fs: List[ApiFlight], since: String) = {
    log.info(s"Flights before change: $flights")

    val inboundFlightIds: Set[Int] = fs.map(_.FlightID).toSet
    val existingFlightIds: Set[Int] = flights.keys.toSet

    val updatingFlightIds = existingFlightIds intersect inboundFlightIds
    val newFlightIds = existingFlightIds diff inboundFlightIds

    log.info(s"New flights ${fs.filter(newFlightIds contains _.FlightID)}")
    log.info(s"Old      fl ${flights.filterKeys(updatingFlightIds).values}")
    log.info(s"Updating fl ${fs.filter(updatingFlightIds contains _.FlightID)}")

    val newFlights = fs.map(f => (f.FlightID, f))
    flights ++= newFlights

    val totalFlightsBeforeFilter = flights.size
    flights.retain((key, flight) => flight.EstDT >= since || flight.SchDT >= since)
    val totalFlightsAfterFilter = flights.size
    log.info(s"dropping ${totalFlightsBeforeFilter - totalFlightsAfterFilter} flights before $since")

    log.info(s"Flights after change: $flights")
    log.info(s"Flights now ${flights.size}")
  }
}
