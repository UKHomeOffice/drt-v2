package drt.client.components

import diode.data.Pot
import diode.{FastEqLowPri, UseValueEq}
import drt.client.SPAMain
import drt.client.SPAMain._
import drt.client.components.TerminalDesksAndQueues.Ideal
import drt.client.components.ToolTips._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.client.services.handlers.GetUserPreferenceIntervalMinutes
import drt.client.spa.TerminalPageMode
import drt.client.spa.TerminalPageModes._
import drt.shared._
import drt.shared.api.WalkTimes
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.StaffEdit
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.HashSet

object TerminalComponent {

  val log: Logger = LoggerFactory.getLogger("TerminalComponent")

  case class Props(terminalPageTab: TerminalPageTabLoc, router: RouterCtl[Loc]) extends FastEqLowPri

  private case class TerminalModel(userSelectedPlanningTimePeriod: Pot[Int],
                                   potShifts: Pot[ShiftAssignments],
                                   potMonthOfShifts: Pot[MonthOfShifts],
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


  class Backend() {
    def render(props: Props): VdomElement = {

      val modelRCP = SPACircuit.connect(model => TerminalModel(
        userSelectedPlanningTimePeriod = model.userSelectedPlanningTimePeriod,
        potShifts = model.shifts,
        potMonthOfShifts = model.monthOfShifts,
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
      ))

      val dialogueStateRCP = SPACircuit.connect(_.maybeStaffDeploymentAdjustmentPopoverState)

      <.div(
        dialogueStateRCP(dialogueStateMP => <.div(dialogueStateMP().map(dialogueState => StaffAdjustmentDialogue(dialogueState)()).whenDefined)),
        modelRCP(modelMP => {
          val model: TerminalModel = modelMP()
          for {
            airportConfig <- model.airportConfig
            loggedInUser <- model.loggedInUserPot
            redListUpdates <- model.redListUpdates
          } yield {
            val timeRangeHours: TimeRangeHours = if (model.viewMode == ViewLive) CurrentWindow() else WholeDayWindow()
            val timeWindow: CustomWindow = timeRange(props.terminalPageTab, timeRangeHours)
            val (viewStart, viewEnd) = viewStartAndEnd(props.terminalPageTab.viewMode.localDate, timeWindow)

            <.div(
              <.div(^.className := "terminal-nav-wrapper", terminalTabs(props, loggedInUser, airportConfig, model.timeMachineEnabled)),
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

                    case Staffing if loggedInUser.roles.contains(StaffEdit) =>
                      <.div(
                        model.potMonthOfShifts.render { ms =>
                          MonthlyStaffing(ms.shifts, props.terminalPageTab, props.router, airportConfig)
                        }
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
    .componentDidMount(_ => Callback(SPACircuit.dispatch(GetUserPreferenceIntervalMinutes())))
    .build

  private def terminalTabs(props: Props, loggedInUser: LoggedInUser, airportConfig: AirportConfig, timeMachineEnabled: Boolean): VdomTagOf[UList] = {
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
        <.a(^.id := "currentTab", "Queues & Arrivals", VdomAttr("data-toggle") := "tab"),
        ^.onClick ==> { e: ReactEventFromInput =>
          e.preventDefault()
          props.router.set(props.terminalPageTab.update(
            mode = Current,
            subMode = subMode,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), viewTypeQueryParam).queryParams
          ))
        }),
      <.li(^.className := tabClass(Planning),
        <.a(^.id := "planning-tab", VdomAttr("data-toggle") := "tab", "Planning"),
        ^.onClick ==> { e: ReactEventFromInput =>
          e.preventDefault()
          props.router.set(props.terminalPageTab.update(
            mode = Planning,
            subMode = subMode,
            queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
          ))
        }
      ),
      if (loggedInUser.roles.contains(StaffEdit))
        <.li(^.className := tabClass(Staffing),
          <.a(^.id := "monthlyStaffingTab", ^.className := "flex-forizontally", VdomAttr("data-toggle") := "tab", "Monthly Staffing", " ", monthlyStaffingTooltip),
          ^.onClick ==> { e: ReactEventFromInput =>
            e.preventDefault()
            props.router.set(props.terminalPageTab.update(
              mode = Staffing,
              subMode = "15",
              queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams
            ))
          }
        ) else "",
      <.li(^.className := tabClass(Dashboard),
        <.a(^.id := "terminalDashboardTab", VdomAttr("data-toggle") := "tab", s"$terminalName Dashboard"), ^.onClick --> {
          props.router.set(
            props.terminalPageTab.update(
              mode = Dashboard,
              subMode = "summary",
              queryParams = props.terminalPageTab.withUrlParameters(UrlDateParameter(None), UrlTimeMachineDateParameter(None)).queryParams)
          )
        }
      )
    )
  }

  def apply(props: Props): VdomElement = component(props)
}
