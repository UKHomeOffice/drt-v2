package drt.client.services

import java.util.UUID

import diode._
import diode.data._
import diode.react.ReactConnector
import drt.client.components.StaffAdjustmentDialogueState
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers._
import drt.shared.CrunchApi._
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared._

import scala.collection.immutable.Map
import scala.concurrent.duration._
import scala.language.postfixOps

sealed trait ViewMode {
  val uUID: UUID = UUID.randomUUID()

  def isDifferentTo(viewMode: ViewMode): Boolean = viewMode.uUID != uUID

  def millis: MillisSinceEpoch = time.millisSinceEpoch

  def dayStart: SDateLike = SDate.midnightOf(time)

  def time: SDateLike

  def isHistoric: Boolean
}

case object ViewLive extends ViewMode {
  def time: SDateLike = SDate.now()

  def isHistoric: Boolean = false
}

case class NoViewMode() extends ViewMode {
  def time: SDateLike = SDate(0L)

  def isHistoric: Boolean = false
}

case class ViewPointInTime(time: SDateLike) extends ViewMode {
  def isHistoric: Boolean = true
}

case class ViewDay(time: SDateLike) extends ViewMode {
  def isHistoric: Boolean = dayStart.millisSinceEpoch < SDate.midnightOf(SDate.now()).millisSinceEpoch
}

case class LoadingState(isLoading: Boolean = false)

case class ClientServerVersions(client: String, server: String)

case class RootModel(applicationVersion: Pot[ClientServerVersions] = Empty,
                     latestUpdateMillis: MillisSinceEpoch = 0L,
                     portStatePot: Pot[PortState] = Empty,
                     forecastPeriodPot: Pot[ForecastPeriodWithHeadlines] = Empty,
                     airportInfos: Map[String, Pot[AirportInfo]] = Map(),
                     airportConfig: Pot[AirportConfig] = Empty,
                     contactDetails: Pot[ContactDetails] = Empty,
                     shifts: Pot[ShiftAssignments] = Empty,
                     monthOfShifts: Pot[MonthOfShifts] = Empty,
                     fixedPoints: Pot[FixedPointAssignments] = Empty,
                     staffMovements: Pot[Seq[StaffMovement]] = Empty,
                     viewMode: ViewMode = NoViewMode(),
                     loadingState: LoadingState = LoadingState(),
                     showActualIfAvailable: Boolean = true,
                     loggedInUserPot: Pot[LoggedInUser] = Empty,
                     userHasPortAccess: Pot[Boolean] = Empty,
                     minuteTicker: Int = 0,
                     keyCloakUsers: Pot[List[KeyCloakUser]] = Empty,
                     selectedUserGroups: Pot[Set[KeyCloakGroup]] = Empty,
                     feedStatuses: Pot[Seq[FeedStatuses]] = Empty,
                     alerts: Pot[List[Alert]] = Empty,
                     maybeStaffDeploymentAdjustmentPopoverState: Option[StaffAdjustmentDialogueState] = None,
                     displayAlertDialog: Pot[Boolean] = Empty,
                     oohStatus: Pot[OutOfHoursStatus] = Empty
                    )

object PollDelay {
  val recoveryDelay: FiniteDuration = 10 seconds
  val loginCheckDelay: FiniteDuration = 30 seconds
  val minuteUpdateDelay: FiniteDuration = 10 seconds
  val oohSupportUpdateDelay: FiniteDuration = 1 minute
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  val blockWidth = 15

  def timeProvider(): MillisSinceEpoch = SDate.now().millisSinceEpoch

  override protected def initialModel = RootModel()

  def currentViewMode: () => ViewMode = () => zoom(_.viewMode).value

  def airportConfigPot(): Pot[AirportConfig] = zoomTo(_.airportConfig).value

  def pointInTimeMillis: MillisSinceEpoch = zoom(_.viewMode).value.millis

  override val actionHandler: HandlerFunction = {
    val composedhandlers: HandlerFunction = composeHandlers(
      new InitialPortStateHandler(currentViewMode, zoomRW(m => (m.portStatePot, m.latestUpdateMillis))((m, v) => m.copy(portStatePot = v._1, latestUpdateMillis = v._2))),
      new PortStateUpdatesHandler(currentViewMode, zoomRW(m => (m.portStatePot, m.latestUpdateMillis))((m, v) => m.copy(portStatePot = v._1, latestUpdateMillis = v._2))),
      new ForecastHandler(zoomRW(_.forecastPeriodPot)((m, v) => m.copy(forecastPeriodPot = v))),
      new AirportCountryHandler(timeProvider, zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new ContactDetailsHandler(zoomRW(_.contactDetails)((m, v) => m.copy(contactDetails = v))),
      new OohForSupportHandler(zoomRW(_.oohStatus)((m, v) => m.copy(oohStatus = v))),
      new ApplicationVersionHandler(zoomRW(_.applicationVersion)((m, v) => m.copy(applicationVersion = v))),
      new ShiftsHandler(currentViewMode, zoomRW(_.shifts)((m, v) => m.copy(shifts = v))),
      new ShiftsForMonthHandler(zoomRW(_.monthOfShifts)((m, v) => m.copy(monthOfShifts = v))),
      new FixedPointsHandler(currentViewMode, zoomRW(_.fixedPoints)((m, v) => m.copy(fixedPoints = v))),
      new StaffMovementsHandler(currentViewMode, zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v))),
      new ViewModeHandler(zoomRW(m => (m.viewMode, m.portStatePot, m.latestUpdateMillis))((m, v) => m.copy(viewMode = v._1, portStatePot = v._2, latestUpdateMillis = v._3)), zoom(_.portStatePot)),
      new LoaderHandler(zoomRW(_.loadingState)((m, v) => m.copy(loadingState = v))),
      new ShowActualDesksAndQueuesHandler(zoomRW(_.showActualIfAvailable)((m, v) => m.copy(showActualIfAvailable = v))),
      new ShowAlertModalDialogHandler(zoomRW(_.displayAlertDialog)((m, v) => m.copy(displayAlertDialog = v))),
      new RetryHandler(zoomRW(identity)((m, v) => m)),
      new ShouldReloadHandler(zoomRW(identity)((m, v) => m)),
      new LoggedInStatusHandler(zoomRW(identity)((m, v) => m)),
      new NoopHandler(zoomRW(identity)((m, v) => m)),
      new LoggedInUserHandler(zoomRW(_.loggedInUserPot)((m, v) => m.copy(loggedInUserPot = v))),
      new UserDashboardHandler(zoomRW(_.loggedInUserPot)((m, v) => m.copy(loggedInUserPot = v))),
      new UserHasPortAccessHandler(zoomRW(_.userHasPortAccess)((m, v) => m.copy(userHasPortAccess = v))),
      new UsersHandler(zoomRW(_.keyCloakUsers)((m, v) => m.copy(keyCloakUsers = v))),
      new EditUserHandler(zoomRW(_.selectedUserGroups)((m, v) => m.copy(selectedUserGroups = v))),
      new MinuteTickerHandler(zoomRW(_.minuteTicker)((m, v) => m.copy(minuteTicker = v))),
      new FeedsStatusHandler(zoomRW(_.feedStatuses)((m, v) => m.copy(feedStatuses = v))),
      new AlertsHandler(zoomRW(_.alerts)((m, v) => m.copy(alerts = v))),
      new StaffAdjustmentDialogueStateHandler(zoomRW(_.maybeStaffDeploymentAdjustmentPopoverState)((m, v) => m.copy(maybeStaffDeploymentAdjustmentPopoverState = v)))
    )

    composedhandlers
  }
}

object SPACircuit extends DrtCircuit
