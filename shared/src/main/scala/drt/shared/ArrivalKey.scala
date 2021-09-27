package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Terminals.Terminal
import drt.shared.api.Arrival
import upickle.default.{macroRW, _}

import scala.collection.immutable.{SortedMap => ISortedMap}
import scala.util.{Failure, Success, Try}

sealed trait VoyageNumberLike {
  def numeric: Int

  def toPaddedString: String
}

case class VoyageNumber(numeric: Int) extends VoyageNumberLike with Ordered[VoyageNumber] {
  override def toString: String = numeric.toString

  def toPaddedString: String = {
    val string = numeric.toString
    val prefix = string.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + string
  }

  override def compare(that: VoyageNumber): Int = numeric.compare(that.numeric)
}

case class InvalidVoyageNumber(exception: Throwable) extends VoyageNumberLike {
  override def toString: String = "invalid"

  override def toPaddedString: String = toString

  override def numeric: Int = 0
}

case object VoyageNumber {
  implicit val rw: ReadWriter[VoyageNumber] = macroRW

  def apply(string: String): VoyageNumberLike = Try(string.toInt) match {
    case Success(value) => VoyageNumber(value)
    case Failure(exception) => InvalidVoyageNumber(exception)
  }
}

case class Operator(code: String)

case class ArrivalStatus(description: String) {
  override def toString: String = description
}

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
            case Some(existingFws) if existingFws.apiFlight == incomingArrival =>
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
