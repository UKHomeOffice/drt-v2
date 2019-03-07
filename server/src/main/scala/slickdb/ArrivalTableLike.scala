package slickdb

import java.sql.Timestamp

import drt.shared
import drt.shared.FlightsApi.TerminalName

import scala.concurrent.Future

trait ArrivalTableLike {
  def selectAll: AggregatedArrivals

  def removeArrival(number: Int, terminalName: TerminalName, scheduledTs: Timestamp): Future[Int]

  def insertOrUpdateArrival(f: shared.Arrival): Future[Int]
}
