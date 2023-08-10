package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, SplitsForArrivals}
import upickle.default.{macroRW, _}

case class FlightUpdatesAndRemovals(arrivalUpdates: Map[Long, ArrivalsDiff],
                                    splitsUpdates: Map[Long, SplitsForArrivals],
                                   ) {
  val latestUpdateMillis: MillisSinceEpoch = {
    val aTs = if (arrivalUpdates.nonEmpty) arrivalUpdates.keys.max else 0L
    val sTs = if (splitsUpdates.nonEmpty) splitsUpdates.keys.max else 0L
    List(aTs, sTs).max
  }

  val nonEmpty: Boolean = arrivalUpdates.nonEmpty || splitsUpdates.nonEmpty

  def ++(other: FlightUpdatesAndRemovals): FlightUpdatesAndRemovals = {
    val combinedArrivalUpdates = other.arrivalUpdates.foldLeft(arrivalUpdates) {
      case (acc, (ts, diff)) => acc.get(ts) match {
        case Some(existing) => acc.updated(ts, existing.copy(toUpdate = existing.toUpdate ++ diff.toUpdate))
        case None => acc.updated(ts, diff)
      }
    }
    val combinedSplitsUpdates = other.splitsUpdates.foldLeft(splitsUpdates) {
      case (acc, (ts, diff)) => acc.get(ts) match {
        case Some(existing) => acc.updated(ts, existing.copy(splits = existing.splits ++ diff.splits))
        case None => acc.updated(ts, diff)
      }
    }
    copy(
      arrivalUpdates = combinedArrivalUpdates,
      splitsUpdates = combinedSplitsUpdates,
    )
  }

  def purgeOldUpdates(expireBeforeMillis: MillisSinceEpoch): FlightUpdatesAndRemovals =
    copy(
      arrivalUpdates = arrivalUpdates.view.filterKeys(_ > expireBeforeMillis).toMap,
      splitsUpdates = splitsUpdates.view.filterKeys(_ > expireBeforeMillis).toMap,
    )

  def updatesSince(sinceMillis: MillisSinceEpoch): FlightUpdatesAndRemovals =
    copy(
      arrivalUpdates = arrivalUpdates.view.filterKeys(_ > sinceMillis).toMap,
      splitsUpdates = splitsUpdates.view.filterKeys(_ > sinceMillis).toMap,
    )

  def add(arrivalsDiff: ArrivalsDiff, ts: MillisSinceEpoch): FlightUpdatesAndRemovals =
    copy(
      arrivalUpdates = arrivalUpdates + (ts -> arrivalsDiff)
    )

  def add(splitsForArrivals: SplitsForArrivals, ts: MillisSinceEpoch): FlightUpdatesAndRemovals =
    copy(
      splitsUpdates = splitsUpdates + (ts -> splitsForArrivals)
    )
}

object FlightUpdatesAndRemovals {
  implicit val rw: ReadWriter[FlightUpdatesAndRemovals] = macroRW

  val empty: FlightUpdatesAndRemovals = FlightUpdatesAndRemovals(
    Map[Long, ArrivalsDiff](),
    Map[Long, SplitsForArrivals](),
  )
}
