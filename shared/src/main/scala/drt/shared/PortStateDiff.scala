package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}

import scala.collection.SortedMap

case class PortStateDiff(flightRemovals: SortedMap[UniqueArrival, RemoveFlight],
                         flightUpdates: SortedMap[UniqueArrival, ApiFlightWithSplits],
                         crunchMinuteUpdates: SortedMap[TQM, CrunchMinute],
                         staffMinuteUpdates: SortedMap[TM, StaffMinute]) {
  val isEmpty: Boolean = flightRemovals.isEmpty && flightUpdates.isEmpty && crunchMinuteUpdates.isEmpty && staffMinuteUpdates.isEmpty

  def window(start: MillisSinceEpoch, end: MillisSinceEpoch): PortStateDiff = PortStateDiff(
    flightRemovals.range(UniqueArrival.atTime(start), UniqueArrival.atTime(end)),
    flightUpdates.range(UniqueArrival.atTime(start), UniqueArrival.atTime(end)),
    crunchMinuteUpdates.range(TQM.atTime(start), TQM.atTime(end)),
    staffMinuteUpdates.range(TM.atTime(start), TM.atTime(end))
  )
}

object PortStateDiff {
  def apply(flightRemovals: Seq[RemoveFlight], flightUpdates: Seq[ApiFlightWithSplits], crunchUpdates: Seq[CrunchMinute], staffUpdates: Seq[StaffMinute]): PortStateDiff = PortStateDiff(
    flightRemovals = SortedMap[UniqueArrival, RemoveFlight]() ++ flightRemovals.map(r => (r.flightKey, r)),
    flightUpdates = SortedMap[UniqueArrival, ApiFlightWithSplits]() ++ flightUpdates.map(fws => (fws.apiFlight.unique, fws)),
    crunchMinuteUpdates = SortedMap[TQM, CrunchMinute]() ++ crunchUpdates.map(cm => (TQM(cm), cm)),
    staffMinuteUpdates = SortedMap[TM, StaffMinute]() ++ staffUpdates.map(sm => (TM(sm), sm))
  )
}
