package drt.client.components

import diode.UseValueEq
import diode.data.{Pending, Pot}
import diode.react.{ModelProxy, ReactConnectProxy}
import drt.client.SPAMain
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.Icon.Icon
import drt.client.components.ToolTips._
import drt.client.components.scenarios.ScenarioSimulationComponent
import drt.client.logger.log
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.{FlightHighlight, _}
import drt.shared.api.WalkTimes
import drt.shared.redlist.RedList
import io.kinoplan.scalajs.react.bridge.WithPropsAndTagsMods
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.vdom.html_<^.{<, VdomAttr, VdomElement, ^, _}
import japgolly.scalajs.react.{Callback, CtorType, Reusability, ScalaComponent}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSimulationUpload, Role, StaffMovementsExport}
import uk.gov.homeoffice.drt.auth._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.HashSet

object TerminalContentComponent {
  case class Props(potShifts: Pot[ShiftAssignments],
                   potFixedPoints: Pot[FixedPointAssignments],
                   potStaffMovements: Pot[StaffMovements],
                   airportConfig: AirportConfig,
                   slaConfigs: SlaConfigs,
                   terminalPageTab: TerminalPageTabLoc,
                   defaultTimeRangeHours: TimeRangeHours,
                   router: RouterCtl[Loc],
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   minuteTicker: Int,
                   featureFlags: Pot[FeatureFlags],
                   redListPorts: Pot[HashSet[PortCode]],
                   redListUpdates: Pot[RedListUpdates],
                   walkTimes: Pot[WalkTimes],
                   paxFeedSourceOrder: List[FeedSource],
                  ) extends UseValueEq

  case class State(activeTab: String, showExportDialogue: Boolean = false)

  implicit val stateReuse: Reusability[State] = Reusability.derive[State]
  implicit val propsReuse: Reusability[Props] = Reusability((a, b) => a == b)

  def viewStartAndEnd(day: LocalDate, range: TimeRangeHours): (SDateLike, SDateLike) = {
    val startOfDay = SDate(day)
    val startOfView = startOfDay.addHours(range.start)
    val endOfView = startOfDay.addHours(range.end)
    (startOfView, endOfView)
  }

  def airportWrapper(portCode: PortCode): ReactConnectProxy[Pot[AirportInfo]] = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  val flightHighlightRCP: ReactConnectProxy[FlightHighlight] = SPACircuit.connect(_.flightHighlight)

  def originMapper(portCode: PortCode, style: html_<^.TagMod): VdomElement = airportWrapper(portCode) {
    proxy: ModelProxy[Pot[AirportInfo]] =>
      <.span(^.className := "flight-origin underline", style,
        proxy().render(ai => Tippy.describe(<.span(s"${ai.airportName}, ${ai.city}, ${ai.country}"), <.abbr(s"${portCode.toString}, ${ai.country}"))),
        proxy().renderEmpty(<.span(portCode.toString))
      )
  }

  class Backend() {
    val arrivalsTableComponent = FlightTable(shortLabel = false, originMapper, splitsGraphComponentColoured)

    def render(props: Props, state: State): TagOf[Div] = {
      val terminal = props.terminalPageTab.terminal
      val queueOrder: Seq[Queue] = props.airportConfig.queueTypeSplitOrder(terminal)

      val desksAndQueuesActive = if (state.activeTab == "desksAndQueues") "active" else ""
      val arrivalsActive = if (state.activeTab == "arrivals") "active" else ""
      val staffingActive = if (state.activeTab == "staffing") "active" else ""
      val simulationsActive = if (state.activeTab == "simulations") "active" else ""

      val desksAndQueuesPanelActive = if (state.activeTab == "desksAndQueues") "active" else "fade"
      val arrivalsPanelActive = if (state.activeTab == "arrivals") "active" else "fade"
      val staffingPanelActive = if (state.activeTab == "staffing") "active" else "fade"
      val viewModeStr = props.terminalPageTab.viewMode.getClass.getSimpleName.toLowerCase

      val timeRangeHours: CustomWindow = timeRange(props)

      val (viewStart, viewEnd) = viewStartAndEnd(props.terminalPageTab.viewMode.localDate, timeRangeHours)
      val terminalName = terminal.toString
      val arrivalsExportForPort = ArrivalsExportComponent(props.airportConfig.portCode, terminal, viewStart)
      val movementsExportDate: LocalDate = props.viewMode match {
        case ViewLive => SDate.now().toLocalDate
        case ViewDay(localDate, _) => localDate
      }

      <.div(^.className := "queues-and-arrivals",
        <.div(^.className := s"view-mode-content $viewModeStr",
          <.div(^.className := "tabs-with-export",
            <.ul(^.className := "nav nav-tabs",
              <.li(^.className := arrivalsActive,
                <.a(^.id := "arrivalsTab", VdomAttr("data-toggle") := "tab", "Arrivals"), ^.onClick --> {
                  GoogleEventTracker.sendEvent(terminalName, "Arrivals", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                  props.router.set(props.terminalPageTab.copy(subMode = "arrivals"))
                }),
              <.li(^.className := desksAndQueuesActive,
                <.a(^.id := "desksAndQueuesTab", VdomAttr("data-toggle") := "tab", "Desks & Queues"), ^.onClick --> {
                  GoogleEventTracker.sendEvent(terminalName, "Desks & Queues", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                  props.router.set(props.terminalPageTab.copy(subMode = "desksAndQueues"))
                }),
              <.li(^.className := staffingActive,
                <.a(^.id := "staffMovementsTab", VdomAttr("data-toggle") := "tab", "Staff Movements", " ", staffMovementsTabTooltip), ^.onClick --> {
                  GoogleEventTracker.sendEvent(terminalName, "Staff Movements", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                  props.router.set(props.terminalPageTab.copy(subMode = "staffing"))
                }),
              displayForRole(
                <.li(^.className := simulationsActive,
                  <.a(^.id := "simulationDayTab", VdomAttr("data-toggle") := "tab", "Simulate Day"), ^.onClick --> {
                    GoogleEventTracker.sendEvent(terminalName, "Simulate Day", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                    props.router.set(props.terminalPageTab.copy(subMode = "simulations"))
                  }),
                ArrivalSimulationUpload, props.loggedInUser
              )
            ),
            <.div(^.className := "exports",
              arrivalsExportForPort(
                props.terminalPageTab.terminal,
                props.terminalPageTab.dateFromUrlOrNow,
                props.loggedInUser,
                props.viewMode),
              exportLink(
                props.terminalPageTab.dateFromUrlOrNow,
                terminalName,
                ExportDeskRecs,
                SPAMain.exportUrl(ExportDeskRecs, props.terminalPageTab.viewMode, terminal),
                None,
                "desk-recs",
              ),
              exportLink(
                props.terminalPageTab.dateFromUrlOrNow,
                terminalName,
                ExportDeployments,
                SPAMain.exportUrl(ExportDeployments, props.terminalPageTab.viewMode, terminal),
                None,
                "deployments"
              ),
              displayForRole(
                exportLink(
                  props.terminalPageTab.dateFromUrlOrNow,
                  terminalName,
                  ExportStaffMovements,
                  SPAMain.absoluteUrl(s"export/staff-movements/${movementsExportDate.toISOString}/$terminal"),
                  None,
                  "staff-movements",
                ),
                StaffMovementsExport,
                props.loggedInUser
              ),
              MultiDayExportComponent(props.airportConfig.portCode, terminal, props.viewMode, props.terminalPageTab.dateFromUrlOrNow, props.loggedInUser))),
          <.div(^.className := "tab-content",
            <.div(^.id := "desksAndQueues", ^.className := s"tab-pane terminal-desk-recs-container $desksAndQueuesPanelActive",
              if (state.activeTab == "desksAndQueues") {
                props.featureFlags.render(features =>
                  TerminalDesksAndQueues(
                    TerminalDesksAndQueues.Props(
                      router = props.router,
                      viewStart = viewStart,
                      hoursToView = timeRangeHours.end - timeRangeHours.start,
                      airportConfig = props.airportConfig,
                      slaConfigs = props.slaConfigs,
                      terminalPageTab = props.terminalPageTab,
                      showActuals = props.showActuals,
                      viewMode = props.viewMode,
                      loggedInUser = props.loggedInUser,
                      featureFlags = features
                    )
                  ))
              } else ""
            ),
            <.div(^.id := "arrivals", ^.className := s"tab-pane in $arrivalsPanelActive", {
              if (state.activeTab == "arrivals") {
                val maybeArrivalsComp = for {
                  features <- props.featureFlags
                  redListPorts <- props.redListPorts
                  redListUpdates <- props.redListUpdates
                  walkTimes <- props.walkTimes
                } yield {
                  flightHighlightRCP { (flightHighlightProxy: ModelProxy[FlightHighlight]) =>
                    val flightHighlight = flightHighlightProxy()
                    arrivalsTableComponent(
                      FlightTable.Props(
                        queueOrder = queueOrder,
                        hasEstChox = props.airportConfig.hasEstChox,
                        loggedInUser = props.loggedInUser,
                        viewMode = props.viewMode,
                        defaultWalkTime = props.airportConfig.defaultWalkTimeMillis(props.terminalPageTab.terminal),
                        hasTransfer = props.airportConfig.hasTransfer,
                        displayRedListInfo = features.displayRedListInfo,
                        redListOriginWorkloadExcluded = RedList.redListOriginWorkloadExcluded(props.airportConfig.portCode, terminal),
                        terminal = terminal,
                        portCode = props.airportConfig.portCode,
                        redListPorts = redListPorts,
                        airportConfig = props.airportConfig,
                        redListUpdates = redListUpdates,
                        walkTimes = walkTimes,
                        viewStart = viewStart,
                        viewEnd = viewEnd,
                        showFlagger = true,
                        paxFeedSourceOrder = props.paxFeedSourceOrder,
                        flightHighlight = flightHighlight
                      )
                    )
                  }
                }
                maybeArrivalsComp.render(x => x)
              } else EmptyVdom
            }),
            displayForRole(
              <.div(^.id := "simulations", ^.className := s"tab-pane in $simulationsActive", {
                if (state.activeTab == "simulations") {
                  ScenarioSimulationComponent(
                    props.viewMode.dayStart.toLocalDate,
                    props.terminalPageTab.terminal,
                    props.airportConfig,
                    props.slaConfigs,
                  )
                } else "not rendering"
              }),
              ArrivalSimulationUpload,
              props.loggedInUser
            ),
            <.div(^.id := "available-staff", ^.className := s"tab-pane terminal-staffing-container $staffingPanelActive",
              if (state.activeTab == "staffing") {
                TerminalStaffing(TerminalStaffing.Props(
                  terminal,
                  props.potShifts,
                  props.potFixedPoints,
                  props.potStaffMovements,
                  props.airportConfig,
                  props.loggedInUser,
                  props.viewMode
                ))
              } else ""
            )
          )
        )
      )
    }
  }

  def exportLink(exportDay: SDateLike,
                 terminalName: String,
                 exportType: ExportType,
                 exportUrl: String,
                 maybeExtraIcon: Option[Icon] = None,
                 title: String,
                ): VdomTagOf[Div] = {
    val keyValue = s"${title.toLowerCase.replace(" ", "-")}-${exportType.toUrlString}"
    <.div(
      ^.key := keyValue,
      MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
        MuiIcons(GetApp)(fontSize = "small"),
        s" $exportType",
        maybeExtraIcon.getOrElse(EmptyVdom),
        ^.className := "btn btn-default",
        ^.href := exportUrl,
        ^.target := "_blank",
        ^.id := s"export-day-${exportType.toUrlString}",
        ^.onClick --> {
          Callback(GoogleEventTracker.sendEvent(terminalName, s"Export $exportType", exportDay.toISODateOnly))
        }
      )
    )
  }

  def displayForRole(node: VdomNode, role: Role, loggedInUser: LoggedInUser): TagMod =
    if (loggedInUser.hasRole(role))
      node
    else
      EmptyVdom

  def timeRange(props: Props): CustomWindow = {
    TimeRangeHours(
      props.terminalPageTab.timeRangeStart.getOrElse(props.defaultTimeRangeHours.start),
      props.terminalPageTab.timeRangeEnd.getOrElse(props.defaultTimeRangeHours.end)
    )
  }

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialStateFromProps(p => State(p.terminalPageTab.subMode))
    .renderBackend[TerminalContentComponent.Backend]
    .componentDidMount(p =>
      Callback {
        val page = s"${p.props.terminalPageTab.terminal}/${p.props.terminalPageTab.mode}/${p.props.terminalPageTab.subMode}"
        val pageWithTime = s"$page/${timeRange(p.props).start}/${timeRange(p.props).end}"
        val pageWithDate = p.props.terminalPageTab.maybeViewDate.map(s => s"$page/$s/${timeRange(p.props).start}/${timeRange(p.props).end}").getOrElse(pageWithTime)
        GoogleEventTracker.sendPageView(pageWithDate)
      }
    )
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
