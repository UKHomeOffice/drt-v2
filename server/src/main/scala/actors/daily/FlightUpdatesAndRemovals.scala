package actors.daily

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.FlightsWithSplitsDiff
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}

import scala.collection.immutable.{Map, Set}

case class FlightUpdatesAndRemovals(updates: Map[UniqueArrival, ApiFlightWithSplits],
                                    removals: Set[(MillisSinceEpoch, UniqueArrival)]) {
  private val log = LoggerFactory.getLogger(getClass)

  def ++(newUpdates: Iterable[ApiFlightWithSplits]): FlightUpdatesAndRemovals =
    copy(updates = updates ++ newUpdates.map(f => (f.unique, f)))

  def purgeOldUpdates(expireBeforeMillis: MillisSinceEpoch): FlightUpdatesAndRemovals = copy(
    updates = updates.filter(_._2.lastUpdated.getOrElse(0L) >= expireBeforeMillis),
    removals = removals.filter {
      case (updated, _) => updated >= expireBeforeMillis
    }
  )

  def updatesSince(sinceMillis: MillisSinceEpoch): FlightsWithSplitsDiff =
    FlightsWithSplitsDiff(
      updates.values.filter(_.lastUpdated.getOrElse(0L) > sinceMillis),
      removals.collect {
        case (updated, removal) if updated > sinceMillis => removal
      }
    )

  def apply(diff: FlightsWithSplitsDiff, latestUpdateMillis: MillisSinceEpoch): FlightUpdatesAndRemovals = {
    val incomingRemovals = diff.arrivalsToRemove
    copy(
      updates = incomingRemovals.foldLeft(updates) {
        case (updatesAcc, removalKey: UniqueArrival) =>
          if (updates.contains(removalKey)) updatesAcc - removalKey else updatesAcc
        case (updatesAcc, _) =>
          log.warn(s"LegacyUniqueArrival is unsupported for streaming updates")
          updatesAcc
      } ++ diff.flightsToUpdate.map(f => (f.unique, f)),
      removals = removals ++ incomingRemovals.collect {
        case ua: UniqueArrival => (latestUpdateMillis, ua)
      }
    )
  }
}

object FlightUpdatesAndRemovals {
  val empty: FlightUpdatesAndRemovals = FlightUpdatesAndRemovals(
    Map[UniqueArrival, ApiFlightWithSplits](),
    Set[(MillisSinceEpoch, UniqueArrival)]())

  def apply(updates: Iterable[ApiFlightWithSplits],
            removals: Iterable[(MillisSinceEpoch, UniqueArrival)]): FlightUpdatesAndRemovals =
    FlightUpdatesAndRemovals(
      updates.map(f => (f.unique, f)).toMap,
      removals.toSet)
}