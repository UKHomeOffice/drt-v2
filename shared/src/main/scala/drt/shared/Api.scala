package drt.shared

import drt.shared.CrunchApi._
import drt.shared.DataUpdates.{FlightUpdates, MinuteUpdates}
import drt.shared.EventTypes.{CI, DC, InvalidEventType}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.MilliTimes.{oneDayMillis, oneMinuteMillis}
import drt.shared.Queues.Queue
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.Terminals.Terminal
import drt.shared.api.{Arrival, FlightCodeSuffix}
import drt.shared.dates.{LocalDate, UtcDate}
import ujson.Js.Value
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.Role
import uk.gov.homeoffice.drt.{Nationality, Urls}
import upickle.default._

import java.lang.Math.round
import java.util.UUID
import scala.collection.immutable.{Map => IMap, SortedMap => ISortedMap}
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

object DeskAndPaxTypeCombinations {
  val egate = "eGate"
  val deskEeaNonMachineReadable = "EEA NMR"
  val deskEea = "EEA"
  val nationalsDeskVisa = "VISA"
  val nationalsDeskNonVisa = "Non-VISA"
}

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

case class PaxAge(years: Int) {
  def isUnder(age: Int): Boolean = years < age

  override def toString: String = s"$years"
}

object PaxAge {
  implicit val rw: ReadWriter[PaxAge] = macroRW
}

case class ApiPaxTypeAndQueueCount(
                                    passengerType: PaxType,
                                    queueType: Queue,
                                    paxCount: Double,
                                    nationalities: Option[IMap[Nationality, Double]],
                                    ages: Option[IMap[PaxAge, Double]]
                                  ) {
  val paxTypeAndQueue: PaxTypeAndQueue = PaxTypeAndQueue(passengerType, queueType)
}

object ApiPaxTypeAndQueueCount {
  implicit val rw: ReadWriter[ApiPaxTypeAndQueueCount] = macroRW
}

sealed trait EventType extends ClassNameForToString

object EventType {
  implicit val rw: ReadWriter[EventType] = macroRW

  def apply(eventType: String): EventType = eventType match {
    case "DC" => DC
    case "CI" => CI
    case _ => InvalidEventType
  }
}

object EventTypes {

  object DC extends EventType

  object CI extends EventType

  object InvalidEventType extends EventType

}

case class Splits(splits: Set[ApiPaxTypeAndQueueCount],
                  source: SplitSource,
                  maybeEventType: Option[EventType],
                  splitStyle: SplitStyle = PaxNumbers) {
  lazy val totalExcludingTransferPax: Double = Splits.totalExcludingTransferPax(splits)
  lazy val totalPax: Double = Splits.totalPax(splits)
}

object Splits {
  def totalExcludingTransferPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.filter(s => s.queueType != Queues.Transfer).toList.map(_.paxCount).sum

  def totalPax(splits: Set[ApiPaxTypeAndQueueCount]): Double = splits.toList.map(s => {
    s.paxCount
  }).sum

  implicit val rw: ReadWriter[Splits] = macroRW
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

object MonthStrings {
  val months = List(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
  )
}

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

  def allPortsAccessible(roles: Set[Role]): Set[PortCode] = AirportConfigs.allPortConfigs
    .filter(airportConfig => roles.contains(airportConfig.role)).map(_.portCode).toSet
}

object ApplicationConfig {
  implicit val rw: ReadWriter[ApplicationConfig] = macroRW
}

object DataUpdates {

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

  def getKeyCloakUserGroups(userId: UUID): Future[Set[KeyCloakGroup]]

  def addUserToGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def removeUserFromGroups(userId: UUID, groups: Set[String]): Future[Unit]

  def getShowAlertModalDialog(): Boolean
}


