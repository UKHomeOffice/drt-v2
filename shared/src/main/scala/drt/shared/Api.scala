package drt.shared

import drt.shared.CrunchApi._
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import uk.gov.homeoffice.drt.Urls
import uk.gov.homeoffice.drt.arrivals.{UniqueArrival, WithLastUpdated, WithTerminal, WithTimeAccessor}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, PortCode}
import upickle.default._

import scala.concurrent.Future
import scala.language.postfixOps


case class MilliDate(_millisSinceEpoch: MillisSinceEpoch) extends Ordered[MilliDate] with WithTimeAccessor {
  lazy val secondsOffset: MillisSinceEpoch = _millisSinceEpoch % 60000

  lazy val millisSinceEpoch: MillisSinceEpoch = _millisSinceEpoch - secondsOffset

  override def compare(that: MilliDate): Int = _millisSinceEpoch.compare(that._millisSinceEpoch)

  override def timeValue: MillisSinceEpoch = _millisSinceEpoch
}

object MilliDate {
  implicit val rw: ReadWriter[MilliDate] = macroRW

  def atTime: MillisSinceEpoch => MilliDate = (time: MillisSinceEpoch) => MilliDate(time)
}

case class StaffTimeSlot(terminal: Terminal,
                         start: MillisSinceEpoch,
                         staff: Int,
                         durationMillis: Int)

case class MonthOfShifts(month: MillisSinceEpoch, shifts: ShiftAssignments)


object MinuteHelper {
  def key(terminalName: Terminal, queue: Queue, minute: MillisSinceEpoch): TQM = TQM(terminalName, queue, minute)

  def key(terminalName: Terminal, minute: MillisSinceEpoch): TM = TM(terminalName, minute)
}

case class FlightsNotReady()

case class RemoveFlight(flightKey: UniqueArrival)

trait MinuteComparison[A <: WithLastUpdated] {
  def maybeUpdated(existing: A, now: MillisSinceEpoch): Option[A]
}

trait PortStateMinutes[MinuteType, IndexType <: WithTimeAccessor] {
  val asContainer: MinutesContainer[MinuteType, IndexType]

  def isEmpty: Boolean

  def nonEmpty: Boolean = !isEmpty

  def addIfUpdated[A <: MinuteComparison[C], B <: WithTerminal[B], C <: WithLastUpdated](maybeExisting: Option[C],
                                                                                         now: MillisSinceEpoch,
                                                                                         existingUpdates: List[C],
                                                                                         incoming: A,
                                                                                         newMinute: () => C): List[C] = {
    maybeExisting match {
      case None => newMinute() :: existingUpdates
      case Some(existing) => incoming.maybeUpdated(existing, now) match {
        case None => existingUpdates
        case Some(updated) => updated :: existingUpdates
      }
    }
  }
}

trait PortStateQueueMinutes extends PortStateMinutes[CrunchMinute, TQM]

trait PortStateQueueLoadMinutes extends PortStateMinutes[PassengersMinute, TQM]

trait PortStateStaffMinutes extends PortStateMinutes[StaffMinute, TM]


case class CrunchResult(firstTimeMillis: MillisSinceEpoch,
                        intervalMillis: MillisSinceEpoch,
                        recommendedDesks: IndexedSeq[Int],
                        waitTimes: Seq[Int])

case class AirportInfo(airportName: String, city: String, country: String, code: String)

object AirportInfo {
  implicit val rw: ReadWriter[AirportInfo] = macroRW
}

case class BuildVersion(version: String, requiresReload: Boolean = false)

object BuildVersion {
  implicit val rw: ReadWriter[BuildVersion] = macroRW
}

case class ApplicationConfig(rootDomain: String, useHttps: Boolean) {
  val urls: Urls = Urls(rootDomain, useHttps)

  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = DrtPortConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet
}

object ApplicationConfig {
  implicit val rw: ReadWriter[ApplicationConfig] = macroRW
}

object DataUpdates {

  trait Combinable[A]  {
    def ++(other: A): A
  }

  trait Updates

  trait FlightUpdates extends Updates

  trait MinuteUpdates extends Updates

}

object PassengerSplits {
  type PaxTypeAndQueueCounts = Seq[ApiPaxTypeAndQueueCount]

  case object FlightsNotFound

  case class FlightNotFound(carrierCode: String, flightCode: String, scheduledArrivalDateTime: MilliDate)

  case class VoyagePaxSplits(destinationPort: String, carrierCode: String,
                             voyageNumber: String,
                             totalPaxCount: Int,
                             scheduledArrivalDateTime: MilliDate,
                             paxSplits: List[ApiPaxTypeAndQueueCount])

}

trait Api {

  def getShifts(maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def getShiftsForMonth(month: MillisSinceEpoch, terminalName: Terminal): Future[ShiftAssignments]

  def updateShifts(shiftsToUpdate: Seq[StaffAssignment]): Unit

  def forecastWeekSummary(startDay: MillisSinceEpoch, terminal: Terminal): Future[Option[ForecastPeriodWithHeadlines]]

  def getLoggedInUser(): LoggedInUser

  def getKeyCloakUsers(): Future[List[KeyCloakUser]]

  def getKeyCloakGroups(): Future[List[KeyCloakGroup]]

  def getKeyCloakUserGroups(userId: String): Future[Set[KeyCloakGroup]]

  def addUserToGroups(userId: String, groups: Set[String]): Future[Unit]

  def removeUserFromGroups(userId: String, groups: Set[String]): Future[Unit]

  def getShowAlertModalDialog(): Boolean
}


