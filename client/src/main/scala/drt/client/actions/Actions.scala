package drt.client.actions

import diode.Action
import diode.data.Pot
import drt.client.components.scenarios.SimulationFormFields
import drt.client.components.{Country, FileUploadState, StaffAdjustmentDialogueState}
import drt.client.services.ViewMode
import drt.shared.CrunchApi._
import drt.shared._
import drt.shared.api.{FlightManifestSummary, ForecastAccuracy, WalkTimes}
import org.scalajs.dom.{Element, File, FormData}
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.egates.{PortEgateBanksUpdates, SetEgateBanksUpdate}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.{SlaConfigs, SlasUpdate}
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.{RedListUpdates, SetRedListUpdate}
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike, UtcDate}

import scala.collection.immutable.HashSet
import scala.concurrent.duration.FiniteDuration

object Actions {

  case object GetLoggedInStatus extends Action

  case object TriggerReload extends Action

  case object GetApplicationVersion extends Action

  case class ScheduleAction(delay: FiniteDuration, action: Action) extends Action

  case object GetLoggedInUser extends Action

  case object GetUserDashboardState extends Action

  case object GetShouldReload extends Action

  case object GetUserHasPortAccess extends Action

  case class SetUserHasPortAccess(hasAccess: Boolean) extends Action

  case class SetLoggedInUser(loggedInUser: LoggedInUser) extends Action

  case class SetApplicationVersion(version: String) extends Action

  case class UpdateServerApplicationVersion(version: String) extends Action

  case class ShowVersionWarning(currentVersion: String, newVersion: String) extends Action

  case class GetInitialPortState(viewMode: ViewMode) extends Action

  case class GetPortStateUpdates(viewMode: ViewMode) extends Action

  case class SchedulePortStateUpdateRequest(viewMode: ViewMode) extends Action

  case class SetPortState(viewMode: ViewMode, portState: PortState) extends Action

  case class UpdatePortStateFromUpdates(viewMode: ViewMode, portStateUpdates: PortStateUpdates) extends Action

  case class GetForecastWeek(startDay: SDateLike, terminal: Terminal) extends Action

  case class SetForecastPeriod(forecastPeriodOption: Option[ForecastPeriodWithHeadlines]) extends Action

  case object GetAirportConfig extends Action

  case object GetSlaConfigs extends Action

  case object GetPaxFeedSourceOrder extends Action

  case class UpdateAirportConfig(airportConfig: AirportConfig) extends Action

  case class UpdateSlaConfigs(configs: SlaConfigs) extends Action

  case class UpdateGetPaxFeedSourceOrder(sources: List[FeedSource]) extends Action

  case object GetOohStatus extends Action

  case class UpdateOohStatus(oohStatus: OutOfHoursStatus) extends Action

  case object GetFeatureFlags extends Action

  case class UpdateFeatureFlags(featureFlags: FeatureFlags) extends Action

  case object GetContactDetails extends Action

  case class UpdateContactDetails(contactDetails: ContactDetails) extends Action

  case class SetFixedPoints(viewMode: ViewMode, fixedPoints: FixedPointAssignments, terminalName: Option[String]) extends Action

  case class SaveFixedPoints(fixedPoints: FixedPointAssignments, terminal: Terminal) extends Action

  case class SetSnackbarMessage(message: Pot[String]) extends Action

  case class GetFixedPoints(viewMode: ViewMode) extends Action

  case class SetShifts(viewMode: ViewMode, shifts: ShiftAssignments, terminalName: Option[String]) extends Action

  case class GetShifts(viewMode: ViewMode) extends Action

  case class SetShiftsForMonth(shiftsForMonth: MonthOfShifts) extends Action

  case class GetShiftsForMonth(month: SDateLike) extends Action

  case class SaveMonthTimeSlotsToShifts(staffTimeSlots: StaffTimeSlotsForTerminalMonth) extends Action

  case class UpdateShifts(shiftsToUpdate: Seq[StaffAssignment]) extends Action

  case class AddStaffMovements(staffMovements: Seq[StaffMovement]) extends Action

  case class RemoveStaffMovements(uUID: String) extends Action

  case class SetStaffMovementsAndPollIfLiveView(viewMode: ViewMode, staffMovements: StaffMovements) extends Action

  case class SetStaffMovements(staffMovements: StaffMovements) extends Action

  case class GetStaffMovements(viewMode: ViewMode) extends Action

  case class SetViewMode(mode: ViewMode) extends Action

  case class ShowLoader() extends Action

  case class HideLoader() extends Action

  case class GetAirportInfos(codes: Set[PortCode]) extends Action

  case class GetRedListPorts(localDate: LocalDate) extends Action

  case class UpdateRedListPorts(codes: HashSet[PortCode], date: LocalDate) extends Action

  case class SetWalktimes(walkTimes: WalkTimes) extends Action

  case class GetManifestSummariesForDate(date: UtcDate) extends Action

  case class GetManifestSummaries(arrivalKeys: Set[ArrivalKey]) extends Action

  case class SetManifestSummaries(summaries: Set[FlightManifestSummary]) extends Action

  case object GetPassengerInfoForCurrentFlights extends Action

  case object GetPassengerInfoForFlights extends Action

  case class UpdateAirportInfo(code: PortCode, info: Option[AirportInfo]) extends Action

  case class UpdateAirportInfos(infos: Map[PortCode, AirportInfo]) extends Action

  case class GetArrivalSources(unique: UniqueArrival) extends Action

  case class GetArrivalSourcesForPointInTime(pointInTime: SDateLike, unique: UniqueArrival) extends Action

  case class UpdateArrivalSources(uniqueArrival: UniqueArrival, arrivalSources: List[Option[FeedSourceArrival]]) extends Action

  case object RemoveArrivalSources extends Action

  case class UpdateShowActualDesksAndQueues(state: Boolean) extends Action

  case object UpdateMinuteTicker extends Action

  case class RetryActionAfter(action: Action, delay: FiniteDuration) extends Action

  case class DoNothing() extends Action

  case class GetAlerts(since: MillisSinceEpoch) extends Action

  case class SetAlerts(alerts: List[Alert], since: MillisSinceEpoch) extends Action

  case object DeleteAllAlerts extends Action

  case class SaveAlert(alert: Alert) extends Action

  case object GetRedListUpdates extends Action

  case class SaveRedListUpdate(setRedListUpdate: SetRedListUpdate) extends Action

  case class DeleteRedListUpdate(effectiveFrom: MillisSinceEpoch) extends Action

  case class SetRedListUpdates(updates: RedListUpdates) extends Action

  case object GetPortEgateBanksUpdates extends Action

  case class SaveEgateBanksUpdate(setEgateBankUpdate: SetEgateBanksUpdate) extends Action

  case class DeleteEgateBanksUpdate(terminal: Terminal, effectiveFrom: MillisSinceEpoch) extends Action

  case class SetEgateBanksUpdates(updates: PortEgateBanksUpdates) extends Action

  case class SaveSlasUpdate(setSlasUpdate: SlasUpdate) extends Action

  case class RemoveSlasUpdate(effectiveFrom: MillisSinceEpoch) extends Action

  case class UpdateStaffAdjustmentDialogueState(maybeNewState: Option[StaffAdjustmentDialogueState]) extends Action

  case class FileUploadStatus(fileUploadState: FileUploadState) extends Action

  case class FileUploadInProgress() extends Action

  case class ForecastFileUploadAction(portCode: String, file: File) extends Action

  case class ResetFileUpload() extends Action

  case class GetSimulation(simulation: SimulationFormFields) extends Action

  case class SetSimulation(simulationResult: SimulationResult) extends Action

  case object ReSetSimulation extends Action

  case object GetGateStandWalktime extends Action

  case class UpdateGateStandWalktime(walkTimes: WalkTimes) extends Action

  case class RequestForecastRecrunch(recalculateSplits: Boolean) extends Action

  object RequestRecalculateArrivals extends Action

  case class GetForecastAccuracy(localDate: LocalDate) extends Action

  case object ClearForecastAccuracy extends Action

  case class UpdateForecastAccuracy(forecastAccuracy: ForecastAccuracy) extends Action

  case class SetTimeMachineDate(date: SDateLike) extends Action

  case class AddFlaggedNationality(country: Country) extends Action

  case class RemoveFlaggedNationality(country: Country) extends Action

  case object ClearFlaggedNationalities extends Action

  case class SetNationalityFlaggerOpen(open: Boolean) extends Action

  case class UpdateNationalityFlaggerInputText(value: String) extends Action
}
