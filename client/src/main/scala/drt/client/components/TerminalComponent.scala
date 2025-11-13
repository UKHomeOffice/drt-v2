package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain
import drt.client.SPAMain._
import drt.client.actions.Actions.{GetAllShiftAssignments, GetForecast}
import drt.client.components.TerminalDesksAndQueues.Recommended
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.handlers.GetShifts
import drt.client.services._
import drt.client.spa.TerminalPageMode
import drt.client.spa.TerminalPageModes._
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import drt.shared.api.WalkTimes
import io.kinoplan.scalajs.react.material.ui.core.MuiTypography
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.models.{CrunchMinute, UserPreferences}
import uk.gov.homeoffice.drt.ports.Queues.Transfer
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, AirportInfo, FeedSource, PortCode, Queues}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.service.QueueConfig
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable
import scala.collection.immutable.{HashSet, SortedMap}
import scala.util.Try

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]) extends FastEqLowPri

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a.terminalPageTab == b.terminalPageTab)

  private case class TerminalModel(userPreferencesPot: Pot[UserPreferences],
                                   dayOfShiftAssignmentsPot: Pot[ShiftAssignments],
                                   fixedPointsPot: Pot[FixedPointAssignments],
                                   staffMovementsPot: Pot[StaffMovements],
                                   removedStaffMovements: Set[String],
                                   airportConfigPot: Pot[AirportConfig],
                                   loadingState: LoadingState,
                                   showActuals: Boolean,
                                   loggedInUserPot: Pot[LoggedInUser],
                                   viewMode: ViewMode,
                                   featureFlagsPot: Pot[FeatureFlags],
                                   redListPortsPot: Pot[HashSet[PortCode]],
                                   redListUpdatesPot: Pot[RedListUpdates],
                                   timeMachineEnabled: Boolean,
                                   walkTimesPot: Pot[WalkTimes],
                                   paxFeedSourceOrder: List[FeedSource],
                                   shiftsPot: Pot[Seq[Shift]],
                                   addedStaffMovementMinutes: Map[TM, Seq[StaffMovementMinute]],
                                   allShiftAssignments: Pot[ShiftAssignments],
                                  ) extends UseValueEq

  private val activeClass = "active"

  private def timeRange(terminalPageTab: TerminalPageTabLoc, defaultTimeRangeHours: TimeRangeHours): CustomWindow =
    TimeRangeHours(
      terminalPageTab.timeRangeStart.getOrElse(defaultTimeRangeHours.start),
      terminalPageTab.timeRangeEnd.getOrElse(defaultTimeRangeHours.end)
    )

  def viewStartAndEnd(day: LocalDate, range: TimeRangeHours): (SDateLike, SDateLike) = {
    val startOfDay = SDate(day)
    val startOfView = startOfDay.addHours(range.startInt)
    val endOfView = startOfDay.addHours(range.endInt)
    (startOfView, endOfView)
  }

  class Backend {
    def render(props: Props): VdomElement = {

      val modelRCP = SPACircuit.connect(model => TerminalModel(
        userPreferencesPot = model.userPreferences,
        dayOfShiftAssignmentsPot = model.dayOfShiftAssignments,
        fixedPointsPot = model.fixedPoints,
        staffMovementsPot = model.staffMovements,
        removedStaffMovements = model.removedStaffMovements,
        airportConfigPot = model.airportConfig,
        loadingState = model.loadingState,
        showActuals = model.showActualIfAvailable,
        loggedInUserPot = model.loggedInUserPot,
        viewMode = model.viewMode,
        featureFlagsPot = model.featureFlags,
        redListPortsPot = model.redListPorts,
        redListUpdatesPot = model.redListUpdates,
        timeMachineEnabled = model.maybeTimeMachineDate.isDefined,
        walkTimesPot = model.gateStandWalkTime,
        paxFeedSourceOrder = model.paxFeedSourceOrder,
        shiftsPot = model.shifts,
        addedStaffMovementMinutes = model.addedStaffMovementMinutes,
        allShiftAssignments = model.allShiftAssignments,
      ))

      val dialogueStateRCP = SPACircuit.connect(_.maybeStaffDeploymentAdjustmentPopoverState)

      <.div(
        dialogueStateRCP(dialogueStateMP => <.div(dialogueStateMP().map(dialogueState => StaffAdjustmentDialogue(dialogueState)()).whenDefined)),
        modelRCP(modelMP => {
          val terminalModel: TerminalModel = modelMP()

          for {
            featureFlags <- terminalModel.featureFlagsPot
            airportConfig <- terminalModel.airportConfigPot
            loggedInUser <- terminalModel.loggedInUserPot
            redListUpdates <- terminalModel.redListUpdatesPot
            userPreferences <- terminalModel.userPreferencesPot
            shifts <- terminalModel.shiftsPot
          } yield {
            val timeRangeHours: TimeRangeHours = if (terminalModel.viewMode == ViewLive) CurrentWindow() else WholeDayWindow()
            val timeWindow: CustomWindow = timeRange(props.terminalPageTab, timeRangeHours)
            val (viewStart, viewEnd) = viewStartAndEnd(props.terminalPageTab.viewMode.localDate, timeWindow)
            <.div(
              <.div(^.className := "terminal-nav-wrapper",
                terminalTabs(props, loggedInUser, airportConfig, terminalModel.timeMachineEnabled, featureFlags.enableShiftPlanningChange)),
              <.div(^.className := "tab-content", {
                val rcp = SPACircuit.connect(m =>
                  (m.minuteTicker,
                    m.portStatePot,
                    m.airportInfos,
                    m.slaConfigs,
                    m.flightManifestSummaries,
                    m.arrivalSources,
                    m.simulationResult,
                    m.flightHighlight
                  ))

                rcp { mp =>
                  val (mt, ps, ai, slas, manSums, arrSources, simRes, fhl) = mp()
                  val terminal = props.terminalPageTab.terminal
                  val queuesPot = terminalModel.airportConfigPot.map { ac =>
                    QueueConfig.queuesForDateRangeAndTerminal(ac.queuesByTerminal)(viewStart.toLocalDate, viewEnd.toLocalDate, terminal)
                      .filterNot(_ == Transfer)
                      .toList
                  }
                  val viewInterval = userPreferences.desksAndQueuesIntervalMinutes

                  props.terminalPageTab.mode match {
                    case Current =>
                      val headerClass = if (terminalModel.timeMachineEnabled) "terminal-content-header__time-machine" else ""

                      def filterFlights(flights: List[ApiFlightWithSplits],
                                        filter: String,
                                        airportInfos: Map[PortCode, Pot[AirportInfo]]): List[ApiFlightWithSplits] = {
                        flights.filter(f => f.apiFlight.flightCodeString.toLowerCase.contains(filter.toLowerCase)
                          || f.apiFlight.Origin.iata.toLowerCase.contains(filter.toLowerCase)
                          || airportInfos.get(f.apiFlight.Origin).exists(airportInfo => airportInfo
                          .exists(_.country.toLowerCase.contains(filter.toLowerCase))))
                      }

                      def flightsForWindow(start: SDateLike, end: SDateLike): Pot[List[ApiFlightWithSplits]] = ps.map { ps =>
                        val psw = ps.window(start, end, terminalModel.paxFeedSourceOrder)
                        filterFlights(psw.flights.values.toList, fhl.filterFlightSearch, ai)
                      }

                      val hoursToView = timeWindow.endInt - timeWindow.startInt

                      val windowCrunchSummaries = queuesPot.flatMap(queues => ps.map(ps => ps.crunchSummary(viewStart, hoursToView * 4, viewInterval, terminal, queues)))
                      val windowStaffRecs: Pot[SortedMap[Long, Int]] = queuesPot.flatMap(queues => ps.map(ps => ps.queueRecStaffSummary(viewStart, hoursToView * 4, viewInterval, terminal, queues)))
                      val windowStaffDeps: Pot[SortedMap[Long, Option[Int]]] = queuesPot.flatMap(queues => ps.map(ps => ps.queueDepStaffSummary(viewStart, hoursToView * 4, viewInterval, terminal, queues)))
                      val dayCrunchSummaries: Pot[SortedMap[Long, Map[Queues.Queue, CrunchMinute]]] = queuesPot.flatMap(queues => ps.map(_.crunchSummary(viewStart.getLocalLastMidnight, 96 * 4, viewInterval, terminal, queues)))
                      val windowStaffSummaries = ps.map(_.staffSummary(viewStart, hoursToView * 4, viewInterval, terminal).toMap)

                      val hasStaff = windowStaffSummaries.exists(_.values.exists(_.available > 0))
                      val defaultDesksAndQueuesViewType = props.terminalPageTab.queryParams.get("viewType") match {
                        case Some(viewType) => viewType
                        case None => if (hasStaff) "deployments" else "ideal"
                      }
                      <.div(
                        MuiTypography(variant = "h2")(s"Queues & Arrivals"),
                        <.div(^.className := s"terminal-content-header $headerClass",
                          DaySelectorComponent(
                            DaySelectorComponent.Props(
                              router = props.router,
                              terminalPageTab = props.terminalPageTab,
                              loadingState = terminalModel.loadingState,
                            )),
                          PcpPaxSummariesComponent(terminalModel.viewMode, mt, ps.map(_.crunchMinutes.values.toSeq))
                        ),
                        TerminalContentComponent(TerminalContentComponent.Props(
                          dayOfShiftAssignmentsPot = terminalModel.dayOfShiftAssignmentsPot,
                          potFixedPoints = terminalModel.fixedPointsPot,
                          potStaffMovements = terminalModel.staffMovementsPot,
                          removedStaffMovements = terminalModel.removedStaffMovements,
                          airportConfig = airportConfig,
                          slaConfigs = slas,
                          terminalPageTab = props.terminalPageTab,
                          defaultTimeRangeHours = timeRangeHours,
                          router = props.router,
                          showActuals = terminalModel.showActuals,
                          viewMode = terminalModel.viewMode,
                          loggedInUser = loggedInUser,
                          featureFlags = terminalModel.featureFlagsPot,
                          redListPorts = terminalModel.redListPortsPot,
                          redListUpdates = terminalModel.redListUpdatesPot,
                          walkTimes = terminalModel.walkTimesPot,
                          paxFeedSourceOrder = terminalModel.paxFeedSourceOrder,
                          flights = flightsForWindow(viewStart, viewEnd),
                          flightManifestSummaries = manSums,
                          arrivalSources = arrSources,
                          simulationResult = simRes,
                          flightHighlight = fhl,
                          viewStart = viewStart,
                          hoursToView = hoursToView,
                          windowCrunchSummaries = windowCrunchSummaries,
                          windowStaffRecs = windowStaffRecs,
                          windowStaffDeps = windowStaffDeps,
                          dayCrunchSummaries = dayCrunchSummaries,
                          windowStaffSummaries = windowStaffSummaries,
                          addedStaffMovementMinutes = terminalModel.addedStaffMovementMinutes,
                          defaultDesksAndQueuesViewType = defaultDesksAndQueuesViewType,
                          userPreferences = userPreferences
                        ))
                      )

                    case Dashboard =>
                      TerminalDashboardComponent(
                        TerminalDashboardComponent.Props(
                          terminalPageTabLoc = props.terminalPageTab,
                          airportConfig = airportConfig,
                          slaConfigs = slas,
                          router = props.router,
                          featureFlags = terminalModel.featureFlagsPot,
                          loggedInUser = loggedInUser,
                          redListPorts = terminalModel.redListPortsPot,
                          redListUpdates = redListUpdates,
                          walkTimes = terminalModel.walkTimesPot,
                          paxFeedSourceOrder = terminalModel.paxFeedSourceOrder,
                          portState = ps,
                          flightManifestSummaries = manSums,
                          arrivalSources = arrSources,
                          flightHighlight = fhl,
                          userPreferences = userPreferences,
                        )
                      )

                    case Planning =>
                      <.div(terminalModel.userPreferencesPot.render { userPreferences =>
                        TerminalPlanningComponent(TerminalPlanningComponent.Props(props.terminalPageTab, props.router,
                          userPreferences.userSelectedPlanningTimePeriod,
                          airportConfig))
                      })

                    case Shifts if loggedInUser.roles.contains(StaffEdit) && props.terminalPageTab.subMode == "editShifts" =>
                      <.div(EditShiftsComponent(terminal,
                        props.terminalPageTab.portCodeStr,
                        terminalModel.shiftsPot,
                        props.terminalPageTab.queryParams("shiftName"),
                        props.terminalPageTab.queryParams.get("shiftDate"),
                        props.terminalPageTab.queryParams.get("date"),
                        props.router))

                    case Shifts if loggedInUser.roles.contains(StaffEdit) && Seq("createShifts", "addShift").contains(props.terminalPageTab.subMode) =>
                      <.div(ShiftsComponent(terminal, props.terminalPageTab.portCodeStr, userPreferences, props.terminalPageTab.subMode, props.router))

                    case Staffing if loggedInUser.roles.contains(StaffEdit) && !featureFlags.enableShiftPlanningChange =>
                      <.div(MonthlyStaffingComponent(props.terminalPageTab, props.router, airportConfig, showShiftsStaffing = false, userPreferences, shifts.isEmpty, false, featureFlags.enableShiftPlanningChange))

                    case Shifts if loggedInUser.roles.contains(StaffEdit) && featureFlags.enableShiftPlanningChange =>
                      if (!userPreferences.showStaffingShiftView)
                        <.div(MonthlyStaffingComponent(props.terminalPageTab, props.router, airportConfig, showShiftsStaffing = true, userPreferences, shifts.isEmpty, props.terminalPageTab.subMode == "viewShifts", featureFlags.enableShiftPlanningChange))
                      else {
                        val slotStaffRecs = for {
                          portState <- ps
                          queues <- queuesPot
                        } yield {
                          val cs = portState.crunchSummary(viewStart.getLocalLastMidnight, 1440 / viewInterval, viewInterval, terminal, queues)
                          val ss = portState.staffSummary(viewStart.getLocalLastMidnight, 1440 / viewInterval, viewInterval, terminal)
                          cs.map {
                            case (ts, queues) =>
                              val queueStaff = queues.values.map(_.deskRec).sum
                              val miscStaff = ss.getOrElse(ts, StaffMinute.empty).fixedPoints
                              (ts, queueStaff + miscStaff)
                          }
                        }
                        val staffWarningsEnabled = terminalModel.featureFlagsPot.map(_.enableStaffingPageWarnings).getOrElse(false)
                        MonthlyShifts(
                          terminalPageTab = props.terminalPageTab,
                          router = props.router,
                          airportConfig = airportConfig,
                          userPreferences = userPreferences,
                          shiftCreated = props.terminalPageTab.queryParams.getOrElse("shifts", "") == "created",
                          viewMode = props.terminalPageTab.subMode == "viewShifts",
                          recommendedStaff = slotStaffRecs.getOrElse(Map.empty),
                          shiftsPot = terminalModel.shiftsPot,
                          shiftAssignmentsPot = terminalModel.allShiftAssignments,
                          warningsEnabled = staffWarningsEnabled,
                        )
                      }

                    case Shifts if loggedInUser.roles.contains(StaffEdit) && shifts.isEmpty =>
                      <.div(^.className := "staffing-container-empty",
                        "No staff shifts are currently available. Please visit the ", <.strong("Staffing"), " tab to create new shifts or select a different tab." +
                          "If you have already created shifts, kindly refresh the page."
                      )
                  }
                }
              }
              )
            )
          }
        }.getOrElse(LoadingOverlay())
        ))
    }
  }

  val component: Component[Props, Unit, Backend, CtorType.Props] = ScalaComponent.builder[Props]("Loader")
    .renderBackend[Backend]
    .componentDidMount { m =>
      val date = m.props.terminalPageTab.dateFromUrlOrNow.startOfTheMonth
      val intervalMinutes = Try(m.props.terminalPageTab.queryParams("timeInterval").toInt).toOption.getOrElse(60)

      Callback(SPACircuit.dispatch(GetForecast(date, 31, m.props.terminalPageTab.terminal, intervalMinutes)))
        .flatMap(_ => Callback(SPACircuit.dispatch(
          GetShifts(m.props.terminalPageTab.terminal.toString, m.props.terminalPageTab.queryParams.get("date"), m.props.terminalPageTab.queryParams.get("dayRange"))))
        )
        .flatMap(_ => Callback(SPACircuit.dispatch(GetAllShiftAssignments)))
    }
    .build

  private def terminalTabs(props: Props,
                           loggedInUser: LoggedInUser,
                           airportConfig: AirportConfig,
                           timeMachineEnabled: Boolean,
                           enableShiftPlanningChange: Boolean): VdomTagOf[UList] = {
    val terminalName = props.terminalPageTab.terminal.toString

    val subMode = if (props.terminalPageTab.mode != Current && props.terminalPageTab.mode != Snapshot)
      "arrivals"
    else
      props.terminalPageTab.subMode

    val subModeInterval = if (enableShiftPlanningChange) "60" else "15"

    def tabClass(mode: TerminalPageMode): String = if (props.terminalPageTab.mode == mode) activeClass else ""

    def viewTypeQueryParam: SPAMain.UrlParameter =
      if (airportConfig.idealStaffAsDefault)
        UrlViewType(Option(Recommended))
      else
        UrlViewType(None)

    val timeMachineClass = if (timeMachineEnabled) "time-machine-active" else ""

    <.ul(^.className := "nav nav-tabs",
      <.li(^.className := tabClass(Current) + " " + timeMachineClass,
        props.router.link(props.terminalPageTab.update(
          mode = Current,
          subMode = subMode,
          queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), viewTypeQueryParam).queryParams
        ))(^.id := "currentTab", "Queues & Arrivals", VdomAttr("data-toggle") := "tab")),
      <.li(^.className := tabClass(Planning),
        props.router.link(props.terminalPageTab.update(
          mode = Planning,
          subMode = subMode,
          queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
        ))(^.id := "planning-tab", VdomAttr("data-toggle") := "tab", "Planning"),
      ),
      if (loggedInUser.roles.contains(StaffEdit) && !enableShiftPlanningChange)
        <.li(^.className := tabClass(Staffing),
          props.router.link(props.terminalPageTab.update(
            mode = Staffing,
            subMode = subModeInterval,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
          ))(^.id := "monthlyStaffingTab", ^.className := "flex-horizontally", VdomAttr("data-toggle") := "tab", "Staffing", " ", monthlyStaffingTooltip)
        ) else "",
      if (loggedInUser.roles.contains(StaffEdit) && enableShiftPlanningChange) {
        <.li(^.className := tabClass(Shifts),
          props.router.link(props.terminalPageTab.update(
            mode = Shifts,
            subMode = subModeInterval,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
          ))(^.id := "ShiftsTab", ^.className := "flex-horizontally", VdomAttr("data-toggle") := "tab", "Staffing", " ", monthlyStaffingTooltip)
        )
      } else "",
      <.li(^.className := tabClass(Dashboard),
        props.router.link(props.terminalPageTab.update(
          mode = Dashboard,
          subMode = "summary",
          queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
        ))(^.id := "terminalDashboardTab", VdomAttr("data-toggle") := "tab", s"$terminalName Dashboard")
      )
    )
  }

  def apply(props: Props): VdomElement = component(props)
}
