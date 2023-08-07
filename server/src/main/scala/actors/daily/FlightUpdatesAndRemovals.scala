package actors.daily

import drt.shared.CrunchApi.MillisSinceEpoch
import org.slf4j.LoggerFactory
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, ArrivalsDiff, FlightsWithSplitsDiff, SplitsForArrivals, UniqueArrival}

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

  def apply(diff: ArrivalsDiff, latestUpdateMillis: MillisSinceEpoch): FlightUpdatesAndRemovals = {
    val incomingRemovals = diff.toRemove
    copy(
      updates = incomingRemovals.foldLeft(updates) {
        case (updatesAcc, removalKey: UniqueArrival) =>
          if (updates.contains(removalKey)) updatesAcc - removalKey else updatesAcc
        case (updatesAcc, _) =>
          log.warn(s"LegacyUniqueArrival is unsupported for streaming updates")
          updatesAcc
      } ++ diff.toUpdate.map { case (ua, a) =>
        val fws = updates.get(ua) match {
          case Some(existing) => existing.copy(apiFlight = a, lastUpdated = Option(latestUpdateMillis))
          case None => ApiFlightWithSplits(a, Set(), Option(latestUpdateMillis))
        }
        (ua, fws)
      },
      removals = removals ++ incomingRemovals.collect {
        case ua: UniqueArrival => (latestUpdateMillis, ua)
      }
    )
  }

  def apply(diff: SplitsForArrivals, latestUpdateMillis: MillisSinceEpoch): FlightUpdatesAndRemovals = {
    val map = diff.splits
      .map { case (ua, incoming) =>
        updates.get(ua).map { fws =>
          val updatedSplits = (fws.splits.map(s => (s.source, s)).toMap ++ incoming.map(s => (s.source, s))).values.toSet
          (ua, fws.copy(splits = updatedSplits, lastUpdated = Option(latestUpdateMillis)))
        }
      }
      .collect { case Some(fws) => fws }
      .toMap
    copy(updates = updates ++ map)
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
