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
  def onFlightUpdates(fs: List[ApiFlight]) =  {

    val inboundFlightIds: Set[Int] = fs.map(_.FlightID).toSet
    val existingFlightIds: Set[Int] = flights.keys.toSet

    val updatingFlightIds = existingFlightIds intersect inboundFlightIds
    val newFlightIds = inboundFlightIds diff inboundFlightIds

    log.info(s"New flights ${fs.filter(newFlightIds contains _.FlightID)}")
    log.info(s"Old      fl ${flights.filterKeys(updatingFlightIds).values}")
    log.info(s"Updating fl ${fs.filter(updatingFlightIds contains _.FlightID)}")

    flights ++= fs.map(f => (f.FlightID, f))
    log.info(s"Flights now ${flights.size}")
  }
}
