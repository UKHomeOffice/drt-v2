package controllers


import akka.event.LoggingAdapter
import com.typesafe.config.ConfigFactory
import drt.shared.BestPax.lhrBestPax
import org.joda.time.LocalDate
import org.joda.time.format.DateTimeFormat
import drt.shared.{AirportConfig, Arrival, BestPax, MilliDate}
import services.PcpArrival.{pcpFrom, walkTimeMillisProviderFromCsv}

import scala.language.postfixOps
import scala.collection.mutable

//todo think about where we really want this flight flights, one source of truth?
trait FlightState {
  def log: LoggingAdapter

  def bestPax(f: Arrival): Int

  case class State(flights: Map[Int, Arrival], lastKnownPax: Map[String, Int])

  var state = State(Map(), Map())

  def onFlightUpdates(newFlights: List[Arrival], since: String, domesticPorts: Seq[String]) = {
    logNewFlightInfo(flightState, newFlights)

    val withNewFlights = addNewFlights(flightState, newFlights)
    val withoutOldFlights = filterOutFlightsBeforeThreshold(withNewFlights, since)
    val withoutDomesticFlights = filterOutDomesticFlights(withoutOldFlights, domesticPorts)

    setFlights(withoutDomesticFlights)
  }

  def flightState = state.flights
  def lastKnownPaxState = state.lastKnownPax

  def setFlights(flights: Map[Int, Arrival]) = {
    state = state.copy(flights = flights)
  }

  def setLastKnownPax(lkp: Map[String, Int]): Unit = {
    state = state.copy(lastKnownPax = lkp)
  }

  def setLastKnownPaxForFlight(key: String, pax: Int) = {
    state = state.copy(lastKnownPax = state.lastKnownPax + (key -> pax))
  }

  def addNewFlights(existingFlights: Map[Int, Arrival], newFlights: List[Arrival]) = {
    existingFlights ++ newFlights.map(newFlight => (newFlight.FlightID, newFlight))
  }

  def filterOutFlightsBeforeThreshold(flights: Map[Int, Arrival], since: String): Map[Int, Arrival] = {
    val totalFlightsBeforeFilter = flights.size
    val flightsWithOldDropped = flights.filter { case (_, flight) => flight.EstDT >= since || flight.SchDT >= since }
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

  def addLastKnownPaxNos(newFlights: List[Arrival]) = {
    newFlights.map(f => f.copy(LastKnownPax = lastKnownPaxForFlight(f)))
  }

  def storeLastKnownPaxForFlights(flights: List[Arrival]) = {
    flights.foreach(f => setLastKnownPaxForFlight(lastKnownPaxFlightKey(f), bestPax(f)))
  }

  def lastKnownPaxForFlight(f: Arrival): Option[Int] = {
    lastKnownPaxState.get(lastKnownPaxFlightKey(f))
  }

  def lastKnownPaxFlightKey(flight: Arrival) = {
    flight.IATA + flight.ICAO
  }
}
