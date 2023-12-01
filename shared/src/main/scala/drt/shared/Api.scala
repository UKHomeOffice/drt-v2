
package drt.shared

import drt.shared.CrunchApi._
import uk.gov.homeoffice.drt.arrivals.{WithLastUpdated, WithTimeAccessor}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default._

import scala.language.postfixOps


case class StaffTimeSlot(terminal: Terminal,
                         start: MillisSinceEpoch,
                         staff: Int,
                         durationMillis: Int)

case class MonthOfShifts(month: MillisSinceEpoch, shifts: ShiftAssignments)

object MonthOfShifts {
  implicit val rw: ReadWriter[MonthOfShifts] = macroRW
}


object MinuteHelper {
  def key(terminalName: Terminal, queue: Queue, minute: MillisSinceEpoch): TQM = TQM(terminalName, queue, minute)

  def key(terminalName: Terminal, minute: MillisSinceEpoch): TM = TM(terminalName, minute)
}


trait MinuteComparison[A <: WithLastUpdated] {
  def maybeUpdated(existing: A, now: MillisSinceEpoch): Option[A]
}

trait PortStateMinutes[MinuteType, IndexType <: WithTimeAccessor] {
  val asContainer: MinutesContainer[MinuteType, IndexType]

  def isEmpty: Boolean

  def nonEmpty: Boolean = !isEmpty
}

trait PortStateQueueMinutes extends PortStateMinutes[CrunchMinute, TQM]

trait PortStateQueueLoadMinutes extends PortStateMinutes[PassengersMinute, TQM]

trait PortStateStaffMinutes extends PortStateMinutes[StaffMinute, TM]


case class AirportInfo(airportName: String, city: String, country: String, code: String)

object AirportInfo {
  implicit val rw: ReadWriter[AirportInfo] = macroRW
}

case class BuildVersion(version: String, requiresReload: Boolean = false)

object BuildVersion {
  implicit val rw: ReadWriter[BuildVersion] = macroRW
}
