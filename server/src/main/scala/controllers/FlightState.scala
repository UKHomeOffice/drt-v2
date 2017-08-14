package controllers


import akka.event.LoggingAdapter
import drt.shared._
import services.SDate

import scala.language.postfixOps

//todo think about where we really want this flight flights, one source of truth?
trait FlightState {
  def log: LoggingAdapter

  def bestPax(f: Arrival): Int

  case class State(flights: Map[Int, Arrival], lastKnownPax: Map[String, Int])

  var state = State(Map(), Map())

  def onFlightUpdates(newFlights: List[Arrival], dropFlightsBefore: SDateLike, domesticPorts: Seq[String]) = {
    logNewFlightInfo(flightState, newFlights)

    val withNewFlights = addNewFlights(flightState, newFlights)
    val withoutOldFlights = filterOutFlightsBefore(withNewFlights, dropFlightsBefore)
    val withoutDomesticFlights = filterOutDomesticFlights(withoutOldFlights, domesticPorts)

    setFlights(withoutDomesticFlights)
  }

  def flightState = state.flights
  def lastKnownPaxState = state.lastKnownPax

  def retentionCutoff: SDateLike = {
    SDate.now().addDays(-1)
  }

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

  def filterOutFlightsBefore(flights: Map[Int, Arrival], before: SDateLike): Map[Int, Arrival] = {
    val totalFlightsBeforeFilter = flights.size
    val flightsWithOldDropped = flights.filterNot {
      case (_, flight) if flight.ActChoxDT != "" && flight.ActChoxDT < before.toString =>
        log.info(s"Dropping flight ${flight.IATA} ActChoxDT: ${flight.ActChoxDT} before 1st cutoff ${before.toString}")
        true
      case (_, flight) if flight.SchDT < before.addDays(-1).toString =>
        log.info(s"Dropping flight ${flight.IATA} SchDT: ${flight.SchDT} before 2nd cutoff ${before.toString}")
        true
      case _ => false
    }
    val totalFlightsAfterFilter = flights.size
    log.info(s"Dropped ${totalFlightsBeforeFilter - totalFlightsAfterFilter} flights before $before")
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
