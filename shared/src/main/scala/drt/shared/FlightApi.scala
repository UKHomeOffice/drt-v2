package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.api.Arrival
import uk.gov.homeoffice.drt.ports.ApiFeedSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.collection.immutable.{Map => IMap}
import scala.language.postfixOps

object FlightsApi {

  case class Flights(flights: Iterable[Arrival])

  case class FlightsWithSplits(flights: Map[UniqueArrival, ApiFlightWithSplits]) {
    val isEmpty: Boolean = flights.isEmpty
    val nonEmpty: Boolean = !isEmpty

    def scheduledSince(sinceMillis: MillisSinceEpoch): FlightsWithSplits = FlightsWithSplits(flights.filter {
      case (UniqueArrival(_, _, scheduledMillis, _), _) => scheduledMillis >= sinceMillis
    })

    def scheduledOrPcpWindow(start: SDateLike, end: SDateLike): FlightsWithSplits = {
      val inWindow = flights.filter {
        case (_, fws) =>
          val pcpMatches = fws.apiFlight.hasPcpDuring(start, end)
          val scheduledMatches = start <= fws.apiFlight.Scheduled && end >= fws.apiFlight.Scheduled
          scheduledMatches || pcpMatches
      }
      FlightsWithSplits(inWindow)
    }

    def forTerminal(terminal: Terminal): FlightsWithSplits = {
      val inTerminal = flights.filter {
        case (_, fws) => fws.apiFlight.Terminal == terminal
      }
      FlightsWithSplits(inTerminal)
    }

    def updatedSince(sinceMillis: MillisSinceEpoch): FlightsWithSplits =
      FlightsWithSplits(flights.filter {
        case (_, fws) => fws.lastUpdated.getOrElse(0L) > sinceMillis
      })

    def --(toRemove: Iterable[UniqueArrival]): FlightsWithSplits = FlightsWithSplits(flights -- toRemove)

    def ++(toUpdate: Iterable[(UniqueArrival, ApiFlightWithSplits)]): FlightsWithSplits = FlightsWithSplits(flights ++ toUpdate)

    def +(toAdd: ApiFlightWithSplits): FlightsWithSplits = FlightsWithSplits(flights.updated(toAdd.unique, toAdd))

    def ++(other: FlightsWithSplits): FlightsWithSplits = FlightsWithSplits(flights ++ other.flights)
  }

  object FlightsWithSplits {
    val empty: FlightsWithSplits = FlightsWithSplits(Map[UniqueArrival, ApiFlightWithSplits]())

    def apply(flights: Iterable[ApiFlightWithSplits]): FlightsWithSplits = FlightsWithSplits(flights.map(fws => (fws.unique, fws)).toMap)
  }

  case object NoFlightUpdates extends FlightUpdates

  object SplitsForArrivals {
    val empty: SplitsForArrivals = SplitsForArrivals(Map())
  }

  case class SplitsForArrivals(splits: Map[UniqueArrival, Set[Splits]]) extends FlightUpdates {
    val updatedMillis: Iterable[MillisSinceEpoch] = splits.keys.map(_.scheduled)

    def diff(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): FlightsWithSplitsDiff = {
      val updatedFlights = splits
        .map {
          case (key, newSplits) =>
            flights.flights.get(key)
              .map(fws => (fws, newSplits.diff(fws.splits)))
              .collect {
                case (fws, updatedSplits) if updatedSplits.nonEmpty =>
                  val updatedSources = updatedSplits.map(_.source)
                  val mergedSplits = fws.splits.filterNot(s => updatedSources.contains(s.source)) ++ updatedSplits
                  val updatedArrival = mergedSplits.find(_.source == ApiSplitsWithHistoricalEGateAndFTPercentages) match {
                    case None =>
                      fws.apiFlight
                    case Some(liveSplit) =>
                      fws.apiFlight.copy(
                        ApiPax = Option(Math.round(liveSplit.totalExcludingTransferPax).toInt),
                        FeedSources = fws.apiFlight.FeedSources + ApiFeedSource)
                  }

                  fws.copy(apiFlight = updatedArrival, splits = mergedSplits, lastUpdated = Option(nowMillis))
              }
        }
        .collect { case Some(flight) => flight }

      FlightsWithSplitsDiff(updatedFlights, List())
    }

    def ++(tuple: (UniqueArrival, Set[Splits])): IMap[UniqueArrival, Set[Splits]] = splits + tuple
  }

  case class FlightsWithSplitsDiff(flightsToUpdate: Iterable[ApiFlightWithSplits], arrivalsToRemove: Iterable[UniqueArrivalLike]) extends FlightUpdates {
    def isEmpty: Boolean = flightsToUpdate.isEmpty && arrivalsToRemove.isEmpty

    def nonEmpty: Boolean = !isEmpty

    val updateMinutes: Iterable[MillisSinceEpoch] = flightsToUpdate.flatMap(_.apiFlight.pcpRange)

    def applyTo(flightsWithSplits: FlightsWithSplits,
                nowMillis: MillisSinceEpoch): (FlightsWithSplits, Iterable[MillisSinceEpoch]) = {
      val updated = flightsWithSplits.flights ++ flightsToUpdate.map(f => (f.apiFlight.unique, f.copy(lastUpdated = Option(nowMillis))))

      val minusRemovals: Map[UniqueArrival, ApiFlightWithSplits] = ArrivalsRemoval.removeArrivals(arrivalsToRemove, updated)

      val asMap: IMap[UniqueArrival, ApiFlightWithSplits] = flightsWithSplits.flights

      val minutesFromRemovalsInExistingState: Iterable[MillisSinceEpoch] = arrivalsToRemove
        .flatMap {
          case r: UniqueArrival =>
            asMap.get(r).map(_.apiFlight.pcpRange).getOrElse(List())
          case r: LegacyUniqueArrival =>
            asMap.collect { case (ua, a) if ua.equalsLegacy(r) => a }.flatMap(_.apiFlight.pcpRange)
        }

      val minutesFromExistingStateUpdatedFlights = flightsToUpdate
        .flatMap { fws =>
          asMap.get(fws.unique) match {
            case None => List()
            case Some(f) => f.apiFlight.pcpRange
          }
        }

      val updatedMinutesFromFlights = minutesFromRemovalsInExistingState ++
        updateMinutes ++
        minutesFromExistingStateUpdatedFlights

      (FlightsWithSplits(minusRemovals), updatedMinutesFromFlights)
    }

    lazy val terminals: Set[Terminal] = flightsToUpdate.map(_.apiFlight.Terminal).toSet ++
      arrivalsToRemove.map(_.terminal).toSet

    def ++(other: FlightsWithSplitsDiff): FlightsWithSplitsDiff =
      FlightsWithSplitsDiff(flightsToUpdate ++ other.flightsToUpdate, arrivalsToRemove ++ other.arrivalsToRemove)

    def window(startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch): FlightsWithSplitsDiff =
      FlightsWithSplitsDiff(flightsToUpdate.filter(fws =>
        startMillis <= fws.apiFlight.Scheduled && fws.apiFlight.Scheduled <= endMillis
      ), arrivalsToRemove.filter(ua =>
        startMillis <= ua.scheduled && ua.scheduled <= endMillis
      ))

    def forTerminal(terminal: Terminal): FlightsWithSplitsDiff = FlightsWithSplitsDiff(
      flightsToUpdate.filter(_.apiFlight.Terminal == terminal),
      arrivalsToRemove.filter(_.terminal == terminal)
    )
  }

  object FlightsWithSplitsDiff {
    val empty: FlightsWithSplitsDiff = FlightsWithSplitsDiff(List(), List())
  }

}

class ArrivalsRestorer[A <: WithUnique[UniqueArrival]] {
  var arrivals: Map[UniqueArrival, A] = Map()

  def removeHashLegacies(removals: Iterable[Int]): Unit = removals.foreach(keyToRemove => arrivals = arrivals.filterKeys(_.legacyUniqueId != keyToRemove))

  def applyUpdates(updates: Iterable[A]): Unit = updates.foreach { update =>
    arrivals = arrivals + ((update.unique, update))
  }

  def remove(removals: Iterable[UniqueArrivalLike]): Unit =
    arrivals = ArrivalsRemoval.removeArrivals(removals, arrivals)

  def finish(): Unit = arrivals = Map()
}

object ArrivalsRemoval {
  def removeArrivals[A](removals: Iterable[UniqueArrivalLike], arrivals: Map[UniqueArrival, A]): Map[UniqueArrival, A] = {
    val keys = removals.collect { case k: UniqueArrival => k }
    val minusRemovals = arrivals -- keys
    val legacyKeys = removals.collect { case lk: LegacyUniqueArrival => lk }
    if (legacyKeys.nonEmpty) {
      legacyKeys.foldLeft(minusRemovals) {
        case (acc, legacyKey) => acc.filterKeys(_.legacyUniqueArrival != legacyKey)
      }
    } else minusRemovals
  }
}