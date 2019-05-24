package drt.client.actions

import java.util.UUID

import diode.Action
import drt.client.components.StaffAdjustmentDialogueState
import drt.client.services.ViewMode
import drt.shared.CrunchApi._
import drt.shared.FlightsApi._
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared._

import scala.concurrent.duration.FiniteDuration

object Actions {

  case object GetLoggedInStatus extends Action

  case object TriggerReload extends Action

  case object GetApplicationVersion extends Action

  case object GetLoggedInUser extends Action

  case object GetShouldReload extends Action

  case object GetUserHasPortAccess extends Action

  case class SetUserHasPortAccess(hasAccess: Boolean) extends Action

  case class SetLoggedInUser(loggedInUser: LoggedInUser) extends Action

  case class SetApplicationVersion(version: String) extends Action

  case class UpdateServerApplicationVersion(version: String) extends Action

  case class ShowVersionWarning(currentVersion: String, newVersion: String) extends Action

  case class GetInitialCrunchState(viewMode: ViewMode) extends Action

  case class GetCrunchStateUpdates(viewMode: ViewMode) extends Action

  case class ScheduleCrunchUpdateRequest(viewMode: ViewMode) extends Action

  case class CreateCrunchStateFromPortState(viewMode: ViewMode, portState: PortState) extends Action

  case class UpdateCrunchStateFromUpdates(viewMode: ViewMode, crunchUpdates: CrunchUpdates) extends Action

  case class GetForecastWeek(startDay: SDateLike, terminalName: TerminalName) extends Action

  case class SetForecastPeriod(forecastPeriodOption: Option[ForecastPeriodWithHeadlines]) extends Action

  case object GetAirportConfig extends Action

  case class UpdateAirportConfig(airportConfig: AirportConfig) extends Action

  case class SetFixedPoints(viewMode: ViewMode, fixedPoints: FixedPointAssignments, terminalName: Option[String]) extends Action

  case class SaveFixedPoints(fixedPoints: FixedPointAssignments, terminalName: TerminalName) extends Action

  case class GetFixedPoints(viewMode: ViewMode) extends Action

  case class SetShifts(viewMode: ViewMode, shifts: ShiftAssignments, terminalName: Option[String]) extends Action

  case class GetShifts(viewMode: ViewMode) extends Action

  case class SetShiftsForMonth(shiftsForMonth: MonthOfShifts) extends Action

  case class GetShiftsForMonth(month: SDateLike, terminalName: TerminalName) extends Action

  case class SaveMonthTimeSlotsToShifts(staffTimeSlots: StaffTimeSlotsForTerminalMonth) extends Action

  case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignment]) extends Action

  case class AddStaffMovements(staffMovements: Seq[StaffMovement]) extends Action

  case class RemoveStaffMovements(uUID: UUID) extends Action

  case class SetStaffMovements(viewMode: ViewMode, staffMovements: Seq[StaffMovement]) extends Action

  case class GetStaffMovements(viewMode: ViewMode) extends Action

  case class SetViewMode(mode: ViewMode) extends Action

  case class ShowLoader() extends Action

  case class HideLoader() extends Action

  case class GetAirportInfos(code: Set[String]) extends Action

  case class UpdateAirportInfo(code: String, info: Option[AirportInfo]) extends Action

  case class UpdateAirportInfos(infos: Map[String, AirportInfo]) extends Action

  case class UpdateShowActualDesksAndQueues(state: Boolean) extends Action

  case class UpdateShowAlertModalDialog(state: Boolean) extends Action

  case object GetShowAlertModalDialog extends Action

  case object UpdateMinuteTicker extends Action

  case class RetryActionAfter(action: Action, delay: FiniteDuration) extends Action

  case class DoNothing() extends Action

  case object GetKeyCloakUsers extends Action

  case class GetKeyCloakUser(userId: String) extends Action

  case class SetKeyCloakUsers(users: List[KeyCloakUser]) extends Action

  case class SaveUserGroups(userId: UUID, groupsToAdd: Set[String], groupsToRemove: Set[String]) extends Action

  case class GetUserGroups(userId: UUID) extends Action

  case class SetSelectedUserGroups(groups: Set[KeyCloakGroup]) extends Action

  case class GetAlerts(since: MillisSinceEpoch) extends Action

  case class SetAlerts(alerts: Seq[Alert], since: MillisSinceEpoch) extends Action

  case object DeleteAllAlerts extends Action

  case class SaveAlert(alert: Alert) extends Action

  case class UpdateStaffAdjustmentDialogueState(maybeNewState: Option[StaffAdjustmentDialogueState]) extends Action

}
