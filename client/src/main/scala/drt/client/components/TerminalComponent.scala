package drt.client.components

import diode.AnyAction.aType
import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain
import drt.client.SPAMain._
import drt.client.components.TerminalDesksAndQueues.Ideal
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, _}
import drt.client.services.handlers.{GetShifts, GetUserPreferenceIntervalMinutes}
import drt.client.spa.TerminalPageMode
import drt.client.spa.TerminalPageModes._
import drt.shared._
import drt.shared.api.WalkTimes
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, AirportInfo, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.HashSet

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]) extends FastEqLowPri

  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a.terminalPageTab == b.terminalPageTab)

  private case class TerminalModel(userSelectedPlanningTimePeriod: Pot[Int],
                                   potShifts: Pot[ShiftAssignments],
                                   potStaffShifts: Pot[ShiftAssignments],
                                   potFixedPoints: Pot[FixedPointAssignments],
                                   potStaffMovements: Pot[StaffMovements],
                                   airportConfig: Pot[AirportConfig],
                                   slaConfigs: Pot[SlaConfigs],
                                   loadingState: LoadingState,
                                   showActuals: Boolean,
                                   loggedInUserPot: Pot[LoggedInUser],
                                   viewMode: ViewMode,
                                   featureFlags: Pot[FeatureFlags],
                                   redListPorts: Pot[HashSet[PortCode]],
                                   redListUpdates: Pot[RedListUpdates],
                                   timeMachineEnabled: Boolean,
                                   walkTimes: Pot[WalkTimes],
                                   paxFeedSourceOrder: List[FeedSource],
                                   staffShiftsPot: Pot[Seq[StaffShift]]
                                  ) extends UseValueEq

  private val activeClass = "active"

  private def timeRange(terminalPageTab: TerminalPageTabLoc, defaultTimeRangeHours: TimeRangeHours): CustomWindow =
    TimeRangeHours(
      terminalPageTab.timeRangeStart.getOrElse(defaultTimeRangeHours.start),
      terminalPageTab.timeRangeEnd.getOrElse(defaultTimeRangeHours.end)
    )

  def viewStartAndEnd(day: LocalDate, range: TimeRangeHours): (SDateLike, SDateLike) = {
    val startOfDay = SDate(day)
    val startOfView = startOfDay.addHours(range.start)
    val endOfView = startOfDay.addHours(range.end)
    (startOfView, endOfView)
  }

  class Backend {
    def render(props: Props): VdomElement = {

      val modelRCP = SPACircuit.connect(model => TerminalModel(
        userSelectedPlanningTimePeriod = model.userSelectedPlanningTimePeriod,
        potShifts = model.dayOfShifts,
        potStaffShifts = model.dayOfStaffShifts,
        potFixedPoints = model.fixedPoints,
        potStaffMovements = model.staffMovements,
        airportConfig = model.airportConfig,
        slaConfigs = model.slaConfigs,
        loadingState = model.loadingState,
        showActuals = model.showActualIfAvailable,
        loggedInUserPot = model.loggedInUserPot,
        viewMode = model.viewMode,
        featureFlags = model.featureFlags,
        redListPorts = model.redListPorts,
        redListUpdates = model.redListUpdates,
        timeMachineEnabled = model.maybeTimeMachineDate.isDefined,
        walkTimes = model.gateStandWalkTime,
        paxFeedSourceOrder = model.paxFeedSourceOrder,
        staffShiftsPot = model.staffShifts
      ))

      val dialogueStateRCP = SPACircuit.connect(_.maybeStaffDeploymentAdjustmentPopoverState)

      <.div(
        dialogueStateRCP(dialogueStateMP => <.div(dialogueStateMP().map(dialogueState => StaffAdjustmentDialogue(dialogueState)()).whenDefined)),
        modelRCP(modelMP => {
          val model: TerminalModel = modelMP()
          for {
            featureFlags <- model.featureFlags
            airportConfig <- model.airportConfig
            loggedInUser <- model.loggedInUserPot
            redListUpdates <- model.redListUpdates
            staffShifts <- model.staffShiftsPot
          } yield {
            val timeRangeHours: TimeRangeHours = if (model.viewMode == ViewLive) CurrentWindow() else WholeDayWindow()
            val timeWindow: CustomWindow = timeRange(props.terminalPageTab, timeRangeHours)
            val (viewStart, viewEnd) = viewStartAndEnd(props.terminalPageTab.viewMode.localDate, timeWindow)

            <.div(
              <.div(^.className := "terminal-nav-wrapper",
                terminalTabs(props, loggedInUser, airportConfig, model.timeMachineEnabled, featureFlags.enableShiftPlanningChange, staffShifts.nonEmpty)),
              <.div(^.className := "tab-content", {
                val rcp = SPACircuit.connect(m =>
                  (m.minuteTicker,
                    m.portStatePot,
                    m.airportInfos,
                    m.slaConfigs,
                    m.flightManifestSummaries,
                    m.arrivalSources,
                    m.simulationResult,
                    m.flightHighlight,
                  ))

                rcp { mp =>
                  val (mt, ps, ai, slas, manSums, arrSources, simRes, fhl) = mp()

                  props.terminalPageTab.mode match {
                    case Current =>
                      val headerClass = if (model.timeMachineEnabled) "terminal-content-header__time-machine" else ""

                      def filterFlights(flights: List[ApiFlightWithSplits],
                                        filter: String,
                                        airportInfos: Map[PortCode, Pot[AirportInfo]]): List[ApiFlightWithSplits] = {
                        flights.filter(f => f.apiFlight.flightCodeString.toLowerCase.contains(filter.toLowerCase)
                          || f.apiFlight.Origin.iata.toLowerCase.contains(filter.toLowerCase)
                          || airportInfos.get(f.apiFlight.Origin).exists(airportInfo => airportInfo
                          .exists(_.country.toLowerCase.contains(filter.toLowerCase))))
                      }

                      def flightsForWindow(start: SDateLike, end: SDateLike): Pot[List[ApiFlightWithSplits]] = ps.map { ps =>
                        val psw = ps.window(start, end, model.paxFeedSourceOrder)
                        filterFlights(psw.flights.values.toList, fhl.filterFlightSearch, ai)
                      }

                      val hoursToView = timeWindow.end - timeWindow.start

                      val terminal = props.terminalPageTab.terminal
                      val queues = model.airportConfig.map(_.nonTransferQueues(terminal).toList)
                      val windowCrunchSummaries = queues.flatMap(q => ps.map(ps => ps.crunchSummary(viewStart, hoursToView * 4, 15, terminal, q).toMap))
                      val dayCrunchSummaries = queues.flatMap(q => ps.map(_.crunchSummary(viewStart.getLocalLastMidnight, 96 * 4, 15, terminal, q)))
                      val windowStaffSummaries = ps.map(_.staffSummary(viewStart, hoursToView * 4, 15, terminal).toMap)

                      val hasStaff = windowStaffSummaries.exists(_.values.exists(_.available > 0))
                      val defaultDesksAndQueuesViewType = props.terminalPageTab.queryParams.get("viewType") match {
                        case Some(viewType) => viewType
                        case None => if (hasStaff) "deployments" else "ideal"
                      }

                      <.div(
                        <.div(^.className := s"terminal-content-header $headerClass",
                          DaySelectorComponent(
                            DaySelectorComponent.Props(
                              router = props.router,
                              terminalPageTab = props.terminalPageTab,
                              loadingState = model.loadingState,
                            )),
                          PcpPaxSummariesComponent(model.viewMode, mt, ps.map(_.crunchMinutes.values.toSeq))
                        ),
                        TerminalContentComponent(TerminalContentComponent.Props(
                          potShifts = model.potShifts,
                          potFixedPoints = model.potFixedPoints,
                          potStaffMovements = model.potStaffMovements,
                          airportConfig = airportConfig,
                          slaConfigs = slas,
                          terminalPageTab = props.terminalPageTab,
                          defaultTimeRangeHours = timeRangeHours,
                          router = props.router,
                          showActuals = model.showActuals,
                          viewMode = model.viewMode,
                          loggedInUser = loggedInUser,
                          featureFlags = model.featureFlags,
                          redListPorts = model.redListPorts,
                          redListUpdates = model.redListUpdates,
                          walkTimes = model.walkTimes,
                          paxFeedSourceOrder = model.paxFeedSourceOrder,
                          flights = flightsForWindow(viewStart, viewEnd),
                          flightManifestSummaries = manSums,
                          arrivalSources = arrSources,
                          simulationResult = simRes,
                          flightHighlight = fhl,
                          viewStart = viewStart,
                          hoursToView = hoursToView,
                          windowCrunchSummaries = windowCrunchSummaries,
                          dayCrunchSummaries = dayCrunchSummaries,
                          windowStaffSummaries = windowStaffSummaries,
                          defaultDesksAndQueuesViewType = defaultDesksAndQueuesViewType,
                        ))
                      )

                    case Dashboard =>
                      TerminalDashboardComponent(
                        TerminalDashboardComponent.Props(
                          terminalPageTabLoc = props.terminalPageTab,
                          airportConfig = airportConfig,
                          slaConfigs = slas,
                          router = props.router,
                          featureFlags = model.featureFlags,
                          loggedInUser = loggedInUser,
                          redListPorts = model.redListPorts,
                          redListUpdates = redListUpdates,
                          walkTimes = model.walkTimes,
                          paxFeedSourceOrder = model.paxFeedSourceOrder,
                          portState = ps,
                          flightManifestSummaries = manSums,
                          arrivalSources = arrSources,
                          flightHighlight = fhl,
                        )
                      )

                    case Planning =>
                      <.div(model.userSelectedPlanningTimePeriod.render { timePeriod =>
                        TerminalPlanningComponent(TerminalPlanningComponent.Props(props.terminalPageTab, props.router, timePeriod, airportConfig))
                      })

                    case Staffing if loggedInUser.roles.contains(StaffEdit) && props.terminalPageTab.subMode == "createShifts" =>
                      <.div(drt.client.components.StaffingShifts(props.terminalPageTab.terminal, props.terminalPageTab.portCodeStr, props.router))
                    case Staffing if loggedInUser.roles.contains(StaffEdit) =>
                      <.div(MonthlyStaffing(props.terminalPageTab, props.router, airportConfig, featureFlags.enableShiftPlanningChange, staffShifts.size, false))

                    case Shifts if loggedInUser.roles.contains(StaffEdit) && staffShifts.nonEmpty =>
                      if (props.terminalPageTab.toggleShiftView.isDefined)
                        <.div(MonthlyStaffing(props.terminalPageTab, props.router, airportConfig, featureFlags.enableShiftPlanningChange, staffShifts.size, true))
                      else
                        <.div(MonthlyShifts(props.terminalPageTab, props.router, airportConfig))

                    case Shifts if loggedInUser.roles.contains(StaffEdit) && staffShifts.isEmpty =>
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
    .componentDidMount(p => Callback(SPACircuit.dispatch(GetUserPreferenceIntervalMinutes())) >>
      Callback(SPACircuit.dispatch(GetShifts(p.props.terminalPageTab.portCodeStr, p.props.terminalPageTab.terminal.toString)))
    )

    .build

  private def terminalTabs(props: Props,
                           loggedInUser: LoggedInUser,
                           airportConfig: AirportConfig,
                           timeMachineEnabled: Boolean,
                           enableShiftPlanningChange: Boolean,
                           isStaffShiftsNonEmpty: Boolean): VdomTagOf[UList] = {
    val terminalName = props.terminalPageTab.terminal.toString

    val subMode = if (props.terminalPageTab.mode != Current && props.terminalPageTab.mode != Snapshot)
      "arrivals"
    else
      props.terminalPageTab.subMode

    def tabClass(mode: TerminalPageMode): String = if (props.terminalPageTab.mode == mode) activeClass else ""

    def viewTypeQueryParam: SPAMain.UrlParameter =
      if (airportConfig.idealStaffAsDefault)
        UrlViewType(Option(Ideal))
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
      if (loggedInUser.roles.contains(StaffEdit))
        <.li(^.className := tabClass(Staffing),
          props.router.link(props.terminalPageTab.update(
            mode = Staffing,
            subMode = if (enableShiftPlanningChange) "60" else "15",
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
          ))(^.id := "monthlyStaffingTab", ^.className := "flex-horizontally", VdomAttr("data-toggle") := "tab", "Staffing", " ", monthlyStaffingTooltip)
        ) else "",
      if (loggedInUser.roles.contains(StaffEdit) && enableShiftPlanningChange && isStaffShiftsNonEmpty) {
        <.li(^.className := tabClass(Shifts),
          props.router.link(props.terminalPageTab.update(
            mode = Shifts,
            subMode = if (enableShiftPlanningChange) "60" else "15",
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None), ToggleShiftView(None)).queryParams
          ))(^.id := "ShiftsTab", ^.className := "flex-horizontally", VdomAttr("data-toggle") := "tab", "Shifts", " ", monthlyStaffingTooltip)
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
