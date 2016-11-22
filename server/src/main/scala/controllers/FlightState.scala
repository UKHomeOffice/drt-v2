package controllers


import akka.event.LoggingAdapter
import spatutorial.shared.ApiFlight

import scala.language.postfixOps

//import scala.collection.immutable.Seq
import scala.collection.mutable

//todo think about where we really want this flight state, one source of truth?
trait FlightState {
  def log: LoggingAdapter

  val flights = mutable.Map[Int, ApiFlight]()

  def onFlightUpdates(fs: List[ApiFlight], findFlightUpdates: (mutable.Map[Int, ApiFlight], List[ApiFlight]) => List[(Int, ApiFlight)]) =  {
    val newFlights: List[(Int, ApiFlight)] = findFlightUpdates(flights, fs)
    flights ++= newFlights
    log.info(s"Flights now ${flights.size}")
  }

}

object AllInOneBucket {
  def findFlightUpdates(since: String, log: LoggingAdapter)(currentFlights: mutable.Map[Int, ApiFlight], newFlights: List[ApiFlight]): List[(Int, ApiFlight)] = {
    val updatedFlights = logFlightChanges(log, currentFlights, newFlights)
    val filteredFlights = filterFlights(log, updatedFlights, since)
    filteredFlights
  }

  def logFlightChanges(log: LoggingAdapter, currentFlights: mutable.Map[Int, ApiFlight], fs: List[ApiFlight]): List[(Int, ApiFlight)] = {
    val inboundFlightIds: Set[Int] = fs.map(_.FlightID).toSet
    val existingFlightIds: Set[Int] = currentFlights.keys.toSet

    val updatingFlightIds = existingFlightIds intersect inboundFlightIds
    val newFlightIds = existingFlightIds diff inboundFlightIds

    log.info(s"New flights ${fs.filter(newFlightIds contains _.FlightID)}")
    log.info(s"Old      fl ${currentFlights.filterKeys(updatingFlightIds).values}")
    log.info(s"Updating fl ${fs.filter(updatingFlightIds contains _.FlightID)}")
    val newFlights = fs.map(f => (f.FlightID, f))
    newFlights
  }

  def filterFlights(log: LoggingAdapter, currentFlights: Seq[(Int, ApiFlight)], since: String): List[(Int, ApiFlight)] = {
    log.info(s"dropping flights before $since")

    currentFlights.filter(x => {
      x._2.EstDT >= since || x._2.SchDT >= since
    }).toList
  }
}