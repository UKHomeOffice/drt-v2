package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, UniqueArrival, VoyageNumber, WithTimeAccessor}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import upickle.default.{macroRW, _}

import scala.collection.immutable.{SortedMap => ISortedMap}


case class FeedSourceArrival(feedSource: FeedSource, arrival: Arrival)

object FeedSourceArrival {
  implicit val rw: ReadWriter[FeedSourceArrival] = macroRW
}


case class ArrivalKey(origin: PortCode,
                      voyageNumber: VoyageNumber,
                      scheduled: Long) extends Ordered[ArrivalKey] with WithTimeAccessor {
  override def compare(that: ArrivalKey): Int =
    scheduled.compareTo(that.scheduled) match {
      case 0 => origin.compare(that.origin) match {
        case 0 => voyageNumber.compare(that.voyageNumber)
        case c => c
      }
      case c => c
    }

  override def timeValue: MillisSinceEpoch = scheduled
}

object ArrivalKey {

  implicit val rw: ReadWriter[ArrivalKey] = macroRW

  def apply(arrival: Arrival): ArrivalKey = ArrivalKey(arrival.Origin, arrival.VoyageNumber, arrival.Scheduled)

  def atTime: MillisSinceEpoch => ArrivalKey = (time: MillisSinceEpoch) => ArrivalKey(PortCode(""), VoyageNumber(0), time)
}

case class ArrivalUpdate(old: Arrival, updated: Arrival)

object ArrivalsDiff {
  val empty: ArrivalsDiff = ArrivalsDiff(Seq(), Seq())

  def apply(toUpdate: Iterable[Arrival], toRemove: Iterable[Arrival]): ArrivalsDiff = ArrivalsDiff(
    ISortedMap[UniqueArrival, Arrival]() ++ toUpdate.map(a => (a.unique, a)), toRemove
  )
}

case class ArrivalsDiff(toUpdate: ISortedMap[UniqueArrival, Arrival], toRemove: Iterable[Arrival]) extends FlightUpdates {
  private val minutesFromUpdate: Iterable[MillisSinceEpoch] = toUpdate.values.flatMap(_.pcpRange)
  private val minutesFromRemoval: Iterable[MillisSinceEpoch] = toRemove.flatMap(_.pcpRange)
  val updateMinutes: Iterable[MillisSinceEpoch] = minutesFromUpdate ++ minutesFromRemoval

  def diffWith(flights: FlightsWithSplits, nowMillis: MillisSinceEpoch): FlightsWithSplitsDiff = {
    val updatedFlights = toUpdate
      .map {
        case (key, incomingArrival) =>
          flights.flights.get(key) match {
            case Some(existingFws) if incomingArrival.isEqualTo(existingFws.apiFlight) =>
              None
            case Some(existingFws) =>
              val incomingWithRedListPaxRetained = incomingArrival.copy(RedListPax = existingFws.apiFlight.RedListPax)
              Some(existingFws.copy(apiFlight = incomingWithRedListPaxRetained, lastUpdated = Option(nowMillis)))
            case None =>
              Some(ApiFlightWithSplits(incomingArrival, Set(), Option(nowMillis)))
          }
      }
      .collect { case Some(updatedFlight) => updatedFlight }

    FlightsWithSplitsDiff(updatedFlights, toRemove.map(_.unique))
  }
}


case class CodeShareKeyOrderedByDupes[A](scheduled: Long,
                                         terminal: Terminal,
                                         origin: PortCode,
                                         arrivalKeys: Set[A]) extends Ordered[CodeShareKeyOrderedByDupes[A]] {
  private val dupeCountReversed: Int = 100 - arrivalKeys.size

  override def compare(that: CodeShareKeyOrderedByDupes[A]): Int = dupeCountReversed.compare(that.dupeCountReversed) match {
    case 0 => scheduled.compare(that.scheduled) match {
      case 0 => terminal.compare(that.terminal) match {
        case 0 => origin.compare(that.origin)
        case c => c
      }
      case c => c
    }
    case c => c
  }
}

case class CodeShareKeyOrderedBySchedule(scheduled: Long,
                                         terminal: Terminal,
                                         origin: PortCode) extends Ordered[CodeShareKeyOrderedBySchedule] with WithTimeAccessor {
  override def compare(that: CodeShareKeyOrderedBySchedule): Int = scheduled.compare(that.scheduled) match {
    case 0 => terminal.compare(that.terminal) match {
      case 0 => origin.compare(that.origin)
      case c => c
    }
    case c => c
  }

  override def timeValue: MillisSinceEpoch = scheduled
}

object CodeShareKeyOrderedBySchedule {
  def apply(arrival: Arrival): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(arrival.Scheduled, arrival.Terminal, arrival.Origin)

  def apply(fws: ApiFlightWithSplits): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(fws.apiFlight.Scheduled, fws.apiFlight.Terminal, fws.apiFlight.Origin)

  def apply(scheduled: Long,
            terminalName: String,
            origin: PortCode): CodeShareKeyOrderedBySchedule = CodeShareKeyOrderedBySchedule(scheduled, Terminal(terminalName), origin)

  def atTime: MillisSinceEpoch => CodeShareKeyOrderedBySchedule = (millis: MillisSinceEpoch) => CodeShareKeyOrderedBySchedule(millis, "", PortCode(""))
}
