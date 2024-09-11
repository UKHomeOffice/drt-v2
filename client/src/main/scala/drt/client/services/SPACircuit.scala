package drt.client.services

import diode._
import diode.data._
import diode.react.ReactConnector
import drt.client.components.{FileUploadState, StaffAdjustmentDialogueState}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers._
import drt.shared.CrunchApi._
import drt.shared.KeyCloakApi.{KeyCloakGroup, KeyCloakUser}
import drt.shared.{Country, _}
import drt.shared.api.{FlightManifestSummary, ForecastAccuracy, WalkTimes}
import uk.gov.homeoffice.drt.ABFeature
import uk.gov.homeoffice.drt.arrivals.UniqueArrival
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.egates.PortEgateBanksUpdates
import uk.gov.homeoffice.drt.feedback.UserFeedback
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}
import uk.gov.homeoffice.drt.training.FeatureGuide
import scala.collection.immutable.{HashSet, Map}
import scala.concurrent.duration._
import scala.language.postfixOps

sealed trait ViewMode {
  val uUID: String = UUID.randomUUID().toString()

  def isDifferentTo(viewMode: ViewMode): Boolean = viewMode.uUID != uUID

  def localDate: LocalDate

  protected def dateTime: SDateLike = SDate(localDate)

  lazy val millis: MillisSinceEpoch = dateTime.millisSinceEpoch

  def dayStart: SDateLike = SDate.midnightOf(dateTime)

  def dayEnd: SDateLike = dayStart.addDays(1).addMinutes(-1)

  def isLive: Boolean

  def isHistoric(now: SDateLike): Boolean

  def maybePointInTime: Option[MillisSinceEpoch]
}

case object ViewLive extends ViewMode {
  def localDate: LocalDate = SDate.now().toLocalDate

  override val isLive: Boolean = true

  override def isHistoric(now: SDateLike): Boolean = false

  override def maybePointInTime: Option[MillisSinceEpoch] = None
}

case class ViewDay(localDate: LocalDate, timeMachineDate: Option[SDateLike]) extends ViewMode {
  lazy private val liveToday: Boolean = localDate == SDate.now().toLocalDate && timeMachineDate.isEmpty

  override val isLive: Boolean = if (liveToday) true else false

  override def isHistoric(now: SDateLike): Boolean = timeMachineDate.nonEmpty || dateTime.isHistoricDate(now)

  override def maybePointInTime: Option[MillisSinceEpoch] = timeMachineDate.map(_.millisSinceEpoch)
}

sealed trait ExportType {
  def toUrlString: String
}

object ExportDeskRecs extends ExportType {
  override def toString = "Recommendations"

  override def toUrlString: String = "desk-recs"
}

object ExportDeployments extends ExportType {
  override def toString = "Deployments"

  override def toUrlString: String = "desk-deps"
}

object ExportArrivals extends ExportType {
  override def toString = "Arrivals"

  override def toUrlString: String = toString.toLowerCase
}

object ExportLiveArrivalsFeed extends ExportType {
  override def toString = "Live arrivals feed"

  override def toUrlString: String = "arrivals-feed"
}

case class ExportArrivalsWithRedListDiversions(label: String) extends ExportType {
  override def toString: String = label

  override def toUrlString: String = "arrivals-with-red-list-diversions"
}

case class ExportArrivalsWithoutRedListDiversions(label: String) extends ExportType {
  override def toString: String = label

  override def toUrlString: String = "arrivals"
}

object ExportArrivalsSingleTerminal extends ExportType {
  override def toString = "Single terminal"

  override def toUrlString: String = "arrivals"
}

object ExportArrivalsCombinedTerminals extends ExportType {
  override def toString = "Combined terminals"

  override def toUrlString: String = "arrivals-with-red-list-diversions"
}

object ExportStaffMovements extends ExportType {
  override def toString = "Movements"

  override def toUrlString: String = "staff-movements"
}

case class LoadingState(isLoading: Boolean = false)

case class ClientServerVersions(client: String, server: String)

case class RootModel(applicationVersion: Pot[ClientServerVersions] = Empty,
                     latestFlightUpdateMillis: MillisSinceEpoch = 0L,
                     latestQueueUpdateMillis: MillisSinceEpoch = 0L,
                     latestStaffUpdateMillis: MillisSinceEpoch = 0L,
                     portStatePot: Pot[PortState] = Empty,
                     forecastPeriodPot: Pot[ForecastPeriodWithHeadlines] = Empty,
                     airportInfos: Map[PortCode, Pot[AirportInfo]] = Map(),
                     airportConfig: Pot[AirportConfig] = Empty,
                     arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])] = None,
                     contactDetails: Pot[ContactDetails] = Empty,
                     shifts: Pot[ShiftAssignments] = Empty,
                     monthOfShifts: Pot[MonthOfShifts] = Empty,
                     fixedPoints: Pot[FixedPointAssignments] = Empty,
                     staffMovements: Pot[StaffMovements] = Empty,
                     viewMode: ViewMode = ViewLive,
                     loadingState: LoadingState = LoadingState(),
                     showActualIfAvailable: Boolean = false,
                     loggedInUserPot: Pot[LoggedInUser] = Empty,
                     userHasPortAccess: Pot[Boolean] = Empty,
                     minuteTicker: Pot[Int] = Empty,
                     keyCloakUsers: Pot[List[KeyCloakUser]] = Empty,
                     selectedUserGroups: Pot[Set[KeyCloakGroup]] = Empty,
                     feedStatuses: Pot[Seq[FeedSourceStatuses]] = Empty,
                     alerts: Pot[List[Alert]] = Empty,
                     maybeStaffDeploymentAdjustmentPopoverState: Option[StaffAdjustmentDialogueState] = None,
                     oohStatus: Pot[OutOfHoursStatus] = Empty,
                     featureFlags: Pot[FeatureFlags] = Empty,
                     fileUploadState: Pot[FileUploadState] = Empty,
                     simulationResult: Pot[SimulationResult] = Empty,
                     passengerInfoSummariesByArrival: Pot[Map[ArrivalKey, FlightManifestSummary]] = Ready(Map()),
                     snackbarMessage: Pot[String] = Empty,
                     redListPorts: Pot[HashSet[PortCode]] = Empty,
                     redListUpdates: Pot[RedListUpdates] = Empty,
                     egateBanksUpdates: Pot[PortEgateBanksUpdates] = Empty,
                     gateStandWalkTime: Pot[WalkTimes] = Empty,
                     passengerForecastAccuracy: Pot[ForecastAccuracy] = Empty,
                     featureGuides: Pot[Seq[FeatureGuide]] = Empty,
                     maybeTimeMachineDate: Option[SDateLike] = None,
                     flaggedNationalities: Set[Country] = Set(),
                     flightManifestSummaries: Map[ArrivalKey, FlightManifestSummary] = Map(),
                     paxFeedSourceOrder: List[FeedSource] = List(),
                     showNewFeatureGuideOnLogin: Pot[Boolean] = Empty,
                     featureGuideViewedIds: Pot[Seq[String]] = Empty,
                     dropIns: Pot[Seq[DropIn]] = Empty,
                     dropInRegistrations: Pot[Seq[DropInRegistration]] = Empty,
                     userFeedbacks: Pot[Seq[UserFeedback]] = Empty,
                     abFeatures: Pot[Seq[ABFeature]] = Empty,
                     slaConfigs: Pot[SlaConfigs] = Empty,
                     showFeedbackBanner: Pot[Boolean] = Empty,
                     userSelectedPlanningTimePeriod: Pot[Int] = Empty,
                     flightHighlight: FlightHighlight = FlightHighlight(false, false, false, Seq.empty, Set.empty[Country], ""),
                     minStaff: Pot[TerminalMinStaff] = Empty
                    )

object PollDelay {
  val recoveryDelay: FiniteDuration = 10.seconds
  val loginCheckDelay: FiniteDuration = 10.seconds
  val updatesDelay: FiniteDuration = 10.seconds
  val minuteTickerDelay: FiniteDuration = 1.seconds
  val oohSupportUpdateDelay: FiniteDuration = 10.seconds
  val checkFeatureFlagsDelay: FiniteDuration = 10.seconds
}

trait DrtCircuit extends Circuit[RootModel] with ReactConnector[RootModel] {
  override protected def initialModel: RootModel = RootModel()

  def currentViewMode: () => ViewMode = () => zoom(_.viewMode).value

  override val actionHandler: HandlerFunction = {
    val composedHandlers: HandlerFunction = composeHandlers(
      new InitialPortStateHandler(
        currentViewMode,
        zoomRW(m => (m.portStatePot, m.latestFlightUpdateMillis, m.latestQueueUpdateMillis, m.latestStaffUpdateMillis, m.redListPorts))(
          (m, v) => m.copy(portStatePot = v._1, latestFlightUpdateMillis = v._2, latestQueueUpdateMillis = v._3, latestStaffUpdateMillis = v._4, redListPorts = v._5)
        ),
      ),
      new PortStateUpdatesHandler(
        currentViewMode,
        zoomRW(m => (m.portStatePot, m.latestFlightUpdateMillis, m.latestQueueUpdateMillis, m.latestStaffUpdateMillis))(
          (m, v) => m.copy(portStatePot = v._1, latestFlightUpdateMillis = v._2, latestQueueUpdateMillis = v._3, latestStaffUpdateMillis = v._4)
        ),
        zoom(_.flightManifestSummaries),
        zoom(_.paxFeedSourceOrder),
      ),
      new ForecastHandler(zoomRW(_.forecastPeriodPot)((m, v) => m.copy(forecastPeriodPot = v))),
      new AirportCountryHandler(zoomRW(_.airportInfos)((m, v) => m.copy(airportInfos = v))),
      new FlightManifestSummariesHandler(zoomRW(_.flightManifestSummaries)((m, v) => m.copy(flightManifestSummaries = v))),
      new ArrivalSourcesHandler(zoomRW(_.arrivalSources)((m, v) => m.copy(arrivalSources = v))),
      new AirportConfigHandler(zoomRW(_.airportConfig)((m, v) => m.copy(airportConfig = v))),
      new PaxFeedSourceOrderHandler(zoomRW(_.paxFeedSourceOrder)((m, v) => m.copy(paxFeedSourceOrder = v))),
      new ContactDetailsHandler(zoomRW(_.contactDetails)((m, v) => m.copy(contactDetails = v))),
      new OohForSupportHandler(zoomRW(_.oohStatus)((m, v) => m.copy(oohStatus = v))),
      new FeatureFlagHandler(zoomRW(_.featureFlags)((m, v) => m.copy(featureFlags = v))),
      new ApplicationVersionHandler(zoomRW(_.applicationVersion)((m, v) => m.copy(applicationVersion = v))),
      new ShiftsHandler(currentViewMode, zoomRW(_.shifts)((m, v) => m.copy(shifts = v))),
      new ShiftsForMonthHandler(zoomRW(_.monthOfShifts)((m, v) => m.copy(monthOfShifts = v))),
      new FixedPointsHandler(currentViewMode, zoomRW(_.fixedPoints)((m, v) => m.copy(fixedPoints = v))),
      new StaffMovementsHandler(currentViewMode, zoomRW(_.staffMovements)((m, v) => m.copy(staffMovements = v))),
      new ViewModeHandler(() => SDate.now(), zoomRW(m => (m.viewMode, m.portStatePot, m.latestFlightUpdateMillis))((m, v) => m.copy(viewMode = v._1, portStatePot = v._2, latestFlightUpdateMillis = v._3))),
      new LoaderHandler(zoomRW(_.loadingState)((m, v) => m.copy(loadingState = v))),
      new ShowActualDesksAndQueuesHandler(zoomRW(_.showActualIfAvailable)((m, v) => m.copy(showActualIfAvailable = v))),
      new RetryHandler(zoomRW(identity)((m, _) => m)),
      new LoggedInStatusHandler(zoomRW(identity)((m, _) => m)),
      new NoopHandler(zoomRW(identity)((m, _) => m)),
      new LoggedInUserHandler(zoomRW(_.loggedInUserPot)((m, v) => m.copy(loggedInUserPot = v))),
      new UserDashboardHandler(zoomRW(_.loggedInUserPot)((m, v) => m.copy(loggedInUserPot = v))),
      new UserHasPortAccessHandler(zoomRW(_.userHasPortAccess)((m, v) => m.copy(userHasPortAccess = v))),
      new MinuteTickerHandler(zoomRW(_.minuteTicker)((m, v) => m.copy(minuteTicker = v))),
      new FeedsHandler(zoomRW(_.feedStatuses)((m, v) => m.copy(feedStatuses = v))),
      new AlertsHandler(zoomRW(_.alerts)((m, v) => m.copy(alerts = v))),
      new RedListUpdatesHandler(zoomRW(_.redListUpdates)((m, v) => m.copy(redListUpdates = v))),
      new EgateBanksUpdatesHandler(zoomRW(_.egateBanksUpdates)((m, v) => m.copy(egateBanksUpdates = v))),
      new StaffAdjustmentDialogueStateHandler(zoomRW(_.maybeStaffDeploymentAdjustmentPopoverState)((m, v) => m.copy(maybeStaffDeploymentAdjustmentPopoverState = v))),
      new ForecastFileUploadHandler(zoomRW(_.fileUploadState)((m, v) => m.copy(fileUploadState = v))),
      new SimulationHandler(zoomRW(_.simulationResult)((m, v) => m.copy(simulationResult = v))),
      new SnackbarHandler(zoomRW(_.snackbarMessage)((m, v) => m.copy(snackbarMessage = v))),
      new RedListPortsHandler(zoomRW(_.redListPorts)((m, v) => m.copy(redListPorts = v))),
      new GateStandWalkTimePortsHandler(zoomRW(_.gateStandWalkTime)((m, v) => m.copy(gateStandWalkTime = v))),
      new AppControlHandler(zoomRW(identity)((m, _) => m)),
      new ForecastAccuracyHandler(zoomRW(_.passengerForecastAccuracy)((m, v) => m.copy(passengerForecastAccuracy = v))),
      new FlaggedNationalitiesHandler(zoomRW(_.flaggedNationalities)((m, v) => m.copy(flaggedNationalities = v))),
      new FeatureGuidesHandler(zoomRW(_.featureGuides)((m, v) => m.copy(featureGuides = v))),
      new FeatureGuideDialogHandler(zoomRW(_.showNewFeatureGuideOnLogin)((m, v) => m.copy(showNewFeatureGuideOnLogin = v))),
      new ViewedFeatureGuidesHandler(zoomRW(_.featureGuideViewedIds)((m, v) => m.copy(featureGuideViewedIds = v))),
      new DropInHandler(zoomRW(_.dropIns)((m, v) => m.copy(dropIns = v))),
      new DropInRegistrationsHandler(zoomRW(_.dropInRegistrations)((m, v) => m.copy(dropInRegistrations = v))),
      new UserFeedbackHandler(zoomRW(_.userFeedbacks)((m, v) => m.copy(userFeedbacks = v))),
      new ABFeatureHandler(zoomRW(_.abFeatures)((m, v) => m.copy(abFeatures = v))),
      new SlaConfigsHandler(zoomRW(_.slaConfigs)((m, v) => m.copy(slaConfigs = v))),
      new UserFeedbackBannerHandler(zoomRW(_.showFeedbackBanner)((m, v) => m.copy(showFeedbackBanner = v))),
      new UserPreferencesHandler(zoomRW(_.userSelectedPlanningTimePeriod)((m, v) => m.copy(userSelectedPlanningTimePeriod = v))),
      new FlightHighlightHandler(zoomRW(_.flightHighlight)((m, v) => m.copy(flightHighlight = v))),
      new MinStaffHandler(zoomRW(_.minStaff)((m, v) => m.copy(minStaff = v)))
    )
    composedHandlers
  }
}

object SPACircuit extends DrtCircuit
