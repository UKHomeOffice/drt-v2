package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import upickle.default.{macroRW, _}


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
