package slickdb

import java.sql.Timestamp

import drt.shared
import drt.shared.FlightsApi.TerminalName

trait ArrivalTableLike {
  def selectAll: AggregatedArrivals

  def removeArrival(number: Int, terminalName: TerminalName, scheduledTs: Timestamp): Unit

  def insertOrUpdateArrival(f: shared.Arrival): Unit
}
