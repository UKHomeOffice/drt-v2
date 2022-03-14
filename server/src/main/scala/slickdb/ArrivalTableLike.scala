package slickdb

import uk.gov.homeoffice.drt.arrivals.Arrival
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import java.sql.Timestamp
import scala.concurrent.Future

trait ArrivalTableLike {
  def selectAll: AggregatedArrivals

  def removeArrival(number: Int, terminalName: Terminal, scheduledTs: Timestamp): Future[Int]

  def insertOrUpdateArrival(f: Arrival): Future[Int]
}
