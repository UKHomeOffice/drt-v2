package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.api.Arrival
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default.{macroRW, _}

sealed trait UniqueArrivalLike {
  val number: Int
  val terminal: Terminal
  val scheduled: MillisSinceEpoch
}

trait WithLastUpdated {
  def lastUpdated: Option[MillisSinceEpoch]
}

trait WithTimeAccessor {
  def timeValue: MillisSinceEpoch
}

trait WithUnique[I] {
  def unique: I
}

trait WithTerminal[A] extends Ordered[A] {
  def terminal: Terminal
}

case class LegacyUniqueArrival(number: Int, terminal: Terminal, scheduled: MillisSinceEpoch) extends UniqueArrivalLike

object LegacyUniqueArrival {
  def apply(number: Int,
            terminalName: String,
            scheduled: MillisSinceEpoch): LegacyUniqueArrival = LegacyUniqueArrival(number, Terminal(terminalName), scheduled)
}

case class UniqueArrival(number: Int, terminal: Terminal, scheduled: MillisSinceEpoch, origin: PortCode)
  extends WithTimeAccessor
    with WithTerminal[UniqueArrival]
    with UniqueArrivalLike {

  lazy val legacyUniqueArrival: LegacyUniqueArrival = LegacyUniqueArrival(number, terminal, scheduled)

  override def compare(that: UniqueArrival): Int =
    scheduled.compare(that.scheduled) match {
      case 0 => terminal.compare(that.terminal) match {
        case 0 => number.compare(that.number) match {
          case 0 => origin.iata.compare(that.origin.iata)
          case c => c
        }
        case c => c
      }
      case c => c
    }

  override def timeValue: MillisSinceEpoch = scheduled

  def legacyUniqueId: Int = s"$terminal$scheduled$number".hashCode

  val equalWithinScheduledWindow: (UniqueArrival, Int) => Boolean = (searchKey, windowMillis) =>
    searchKey.number == this.number && searchKey.terminal == this.terminal && Math.abs(searchKey.scheduled - this.scheduled) <= windowMillis

  def equalsLegacy(lua: LegacyUniqueArrival): Boolean =
    lua.number == number && lua.scheduled == scheduled && lua.terminal == terminal
}

object UniqueArrival {
  implicit val rw: ReadWriter[UniqueArrival] = macroRW

  def apply(arrival: Arrival): UniqueArrival = UniqueArrival(arrival.VoyageNumber.numeric, arrival.Terminal, arrival.Scheduled, arrival.Origin)

  def apply(number: Int,
            terminalName: String,
            scheduled: MillisSinceEpoch,
            origin: String): UniqueArrival = UniqueArrival(number, Terminal(terminalName), scheduled, PortCode(origin))

  def atTime: MillisSinceEpoch => UniqueArrival = (time: MillisSinceEpoch) => UniqueArrival(0, "", time, "")
}
