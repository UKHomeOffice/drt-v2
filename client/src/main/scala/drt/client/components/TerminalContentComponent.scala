package drt.client.components

import diode.UseValueEq
import diode.data.{Pending, Pot}
import diode.react.ReactConnectProxy
import drt.client.SPAMain
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.ArrivalsExportComponent.componentFactory
import drt.client.components.Icon.Icon
import drt.client.components.ToolTips.staffMovementsTabTooltip
import drt.client.components.scenarios.ScenarioSimulationComponent
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.CrunchApi.StaffMinute
import drt.shared._
import drt.shared.api.{FlightManifestSummary, WalkTimes}
import drt.shared.redlist.RedList
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiMenu, MuiMenuItem}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, VdomAttr, VdomElement, ^, _}
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.{BackendScope, Callback, CtorType, ReactEvent, ReactEventFromHtml, ScalaComponent}
import org.scalajs.dom.HTMLElement
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, UniqueArrival}
import uk.gov.homeoffice.drt.auth.Roles.{ArrivalSimulationUpload, Role, StaffMovementsExport}
import uk.gov.homeoffice.drt.auth._
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, AirportInfo, FeedSource, PortCode}
import uk.gov.homeoffice.drt.redlist.RedListUpdates
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

import scala.collection.immutable.HashSet
import scala.scalajs.js.JSConverters.JSRichOption

object TerminalContentComponent {
  case class Props(potShifts: Pot[ShiftAssignments],
                   potFixedPoints: Pot[FixedPointAssignments],
                   potStaffMovements: Pot[StaffMovements],
                   airportConfig: AirportConfig,
                   slaConfigs: Pot[SlaConfigs],
                   terminalPageTab: TerminalPageTabLoc,
                   defaultTimeRangeHours: TimeRangeHours,
                   router: RouterCtl[Loc],
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser,
                   featureFlags: Pot[FeatureFlags],
                   redListPorts: Pot[HashSet[PortCode]],
                   redListUpdates: Pot[RedListUpdates],
                   walkTimes: Pot[WalkTimes],
                   paxFeedSourceOrder: List[FeedSource],
                   flights: Pot[Seq[ApiFlightWithSplits]],
                   flightManifestSummaries: Map[ManifestKey, FlightManifestSummary],
                   arrivalSources: Option[(UniqueArrival, Pot[List[Option[FeedSourceArrival]]])],
                   simulationResult: Pot[SimulationResult],
                   flightHighlight: FlightHighlight,
                   viewStart: SDateLike,
                   hoursToView: Int,
                   windowCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchMinute]]],
                   dayCrunchSummaries: Pot[Map[Long, Map[Queue, CrunchMinute]]],
                   windowStaffSummaries: Pot[Map[Long, StaffMinute]],
                   defaultDesksAndQueuesViewType: String,
                   userPreferences: UserPreferences,
                  ) extends UseValueEq

  case class State(activeTab: String, showExportDialogue: Boolean = false, anchorEl: Option[HTMLElement] = None)

  def airportWrapper(portCode: PortCode): ReactConnectProxy[Pot[AirportInfo]] = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  def originMapper(origin: PortCode, maybePrevious: Option[PortCode], style: html_<^.TagMod): VdomElement =
    airportWrapper(origin) { originProxy => {
      val prevPort = maybePrevious.map(previousPortMapper(_, style)) match {
        case Some(prevPort) => <.div(s"via ", prevPort)
        case None => <.span()
      }
      <.span(
        style,
        originProxy().render(ai =>
          <.dfn(^.className := "flight-origin-dfn", Tippy.describe(<.span(s"${ai.airportName}, ${ai.city}, ${ai.country}"),
            <.abbr(^.className := "dotted-underline", s"${origin.toString}")), <.span(s", ${ai.country}", prevPort))),
        originProxy().renderEmpty(<.abbr(^.className := "dotted-underline", <.span(origin.toString, prevPort)))
      )
    }}

  def previousPortMapper(previous: PortCode, style: html_<^.TagMod): VdomElement =
    airportWrapper(previous) { previousProxy =>
      <.span(
        style,
        previousProxy().render(ai =>
          <.dfn(^.className := "flight-origin-dfn", Tippy.describe(<.span(s"${ai.airportName}, ${ai.city}, ${ai.country}"),
            <.abbr(^.className := "dotted-underline", s"${previous.toString}")), s", ${ai.country}")),
        previousProxy().renderEmpty(<.abbr(^.className := "dotted-underline", previous.toString))
      )
    }

  class Backend($: BackendScope[Props, State]) {
    private def handleMenuOpen(event: ReactEventFromHtml): Callback = {
      val target = event.currentTarget.asInstanceOf[HTMLElement]
      $.modState(_.copy(anchorEl = Some(target)))
    }

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
      val terminalName = terminal.toString
      val arrivalsExportForPort = componentFactory(props.airportConfig.terminals)
      val movementsExportDate: LocalDate = props.viewMode match {
        case ViewLive => SDate.now().toLocalDate
        case ViewDay(localDate, _) => localDate
      }

      <.div(^.className := "queues-and-arrivals",
        <.div(^.className := s"view-mode-content $viewModeStr",
          <.div(^.className := "tabs-with-export",
            <.ul(^.className := "nav nav-tabs",
              <.li(^.className := arrivalsActive,
                props.router.link(props.terminalPageTab.copy(subMode = "arrivals"))(
                  ^.id := "arrivalsTab", VdomAttr("data-toggle") := "tab", "Arrivals")
              ),
              <.li(^.className := desksAndQueuesActive,
                props.router.link(props.terminalPageTab.copy(
                  subMode = "desksAndQueues",
                  queryParams = props.terminalPageTab.queryParams.updated("viewType", props.defaultDesksAndQueuesViewType)
                ))(
                  ^.className := "flexed-anchor",
                  ^.id := "desksAndQueuesTab",
                  VdomAttr("data-toggle") := "tab",
                  "Desks & Queues"
                )
              ),
              <.li(^.className := staffingActive,
                props.router.link(props.terminalPageTab.copy(subMode = "staffing"))(
                  ^.className := "flexed-anchor",
                  ^.id := "staffMovementsTab",
                  VdomAttr("data-toggle") := "tab",
                  "Staff Movements",
                  staffMovementsTabTooltip)
              ),
              displayForRole(
                <.li(^.className := simulationsActive,
                  props.router.link(props.terminalPageTab.copy(subMode = "simulations"))(
                    ^.className := "flexed-anchor",
                    ^.id := "simulationDayTab",
                    VdomAttr("data-toggle") := "tab",
                    "Simulate Day"
                  )
                ),
                ArrivalSimulationUpload, props.loggedInUser
              )
            ),
            <.div(^.className := "exports",
              arrivalsExportForPort(
                props.terminalPageTab.terminal,
                props.terminalPageTab.dateFromUrlOrNow,
                props.loggedInUser,
                props.viewMode),
              MuiButton(color = Color.primary, variant = "outlined", size = "medium")(
                MuiIcons(GetApp)(fontSize = "small"),
                "Advanced Downloads",
                ^.className := "btn btn-default",
                ^.onClick ==> handleMenuOpen
              ),
              <.div(^.className := "advanced-downloads-menu",
              MuiMenu(
                disablePortal = true,
                anchorEl = state.anchorEl.orUndefined,
                open = state.anchorEl.isDefined,
                onClose = (_: ReactEvent, _: String) => Callback {
                  $.modState(state => state.copy(anchorEl = None)).runNow()
                })(
                MuiMenuItem()(
                  exportLink(
                    exportDay = props.terminalPageTab.dateFromUrlOrNow,
                    terminalName = terminalName,
                    exportType = ExportDeskRecs(props.terminalPageTab.terminal),
                    exportUrl = SPAMain.exportUrl(ExportDeskRecs(props.terminalPageTab.terminal), props.terminalPageTab.viewMode),
                    title = "desk-recs"
                  )),
                MuiMenuItem()(
                  exportLink(
                    exportDay = props.terminalPageTab.dateFromUrlOrNow,
                    terminalName = terminalName,
                    exportType = ExportDeployments(props.terminalPageTab.terminal),
                    exportUrl = SPAMain.exportUrl(ExportDeployments(props.terminalPageTab.terminal), props.terminalPageTab.viewMode),
                    title = "deployments"
                  )),
                MuiMenuItem()(
                  displayForRole(
                    node = exportLink(
                      exportDay = props.terminalPageTab.dateFromUrlOrNow,
                      terminalName = terminalName,
                      exportType = ExportStaffMovements(props.terminalPageTab.terminal),
                      exportUrl = SPAMain.absoluteUrl(s"export/staff-movements/${movementsExportDate.toISOString}/$terminal"),
                      title = "staff-movements"
                    ),
                    role = StaffMovementsExport,
                    loggedInUser = props.loggedInUser
                  )),
                MuiMenuItem()(
                  MultiDayExportComponent(
                    portCode = props.airportConfig.portCode,
                    terminal = terminal,
                    terminals = props.airportConfig.terminals,
                    viewMode = props.viewMode,
                    selectedDate = props.terminalPageTab.dateFromUrlOrNow,
                    loggedInUser = props.loggedInUser
                  ))
              ))
            )),
          <.div(^.className := "tab-content",
            <.div(^.id := "desksAndQueues", ^.className := s"tab-pane terminal-desk-recs-container $desksAndQueuesPanelActive",
              if (state.activeTab == "desksAndQueues") {
                props.featureFlags.render { features =>
                  TerminalDesksAndQueues(
                    TerminalDesksAndQueues.Props(
                      router = props.router,
                      viewStart = props.viewStart,
                      hoursToView = props.hoursToView,
                      airportConfig = props.airportConfig,
                      slaConfigs = props.slaConfigs,
                      terminalPageTab = props.terminalPageTab,
                      showActuals = props.showActuals,
                      viewMode = props.viewMode,
                      loggedInUser = props.loggedInUser,
                      featureFlags = features,
                      windowCrunchSummaries = props.windowCrunchSummaries,
                      dayCrunchSummaries = props.dayCrunchSummaries,
                      windowStaffSummaries = props.windowStaffSummaries,
                      terminal = terminal,
                    )
                  )
                }
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
                  FlightTable(
                    FlightTable.Props(
                      queueOrder = queueOrder,
                      hasEstChox = props.airportConfig.hasEstChox,
                      loggedInUser = props.loggedInUser,
                      viewMode = props.viewMode,
                      hasTransfer = props.airportConfig.hasTransfer,
                      displayRedListInfo = features.displayRedListInfo,
                      redListOriginWorkloadExcluded = RedList.redListOriginWorkloadExcluded(props.airportConfig.portCode, terminal),
                      terminal = terminal,
                      portCode = props.airportConfig.portCode,
                      redListPorts = redListPorts,
                      airportConfig = props.airportConfig,
                      redListUpdates = redListUpdates,
                      walkTimes = walkTimes,
                      showFlagger = true,
                      paxFeedSourceOrder = props.paxFeedSourceOrder,
                      flightHighlight = props.flightHighlight,
                      flights = props.flights,
                      flightManifestSummaries = props.flightManifestSummaries,
                      arrivalSources = props.arrivalSources,
                      originMapper = originMapper,
                      userPreferences = props.userPreferences,
                      terminalPageTab = props.terminalPageTab
                    )
                  )
                }
                maybeArrivalsComp.render(x => x)
              } else EmptyVdom
            }),
            displayForRole(
              <.div(^.id := "simulations", ^.className := s"tab-pane in $simulationsActive", {
                if (state.activeTab == "simulations") {
                  props.slaConfigs.render(slaConfigs =>
                    ScenarioSimulationComponent(
                      ScenarioSimulationComponent.Props(
                        props.viewMode.dayStart.toLocalDate,
                        props.terminalPageTab.terminal,
                        props.airportConfig,
                        slaConfigs,
                        props.simulationResult,
                      )
                    )
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
        s" ${exportType.linkLabel}",
        maybeExtraIcon.getOrElse(EmptyVdom),
        ^.className := "btn btn-default",
        ^.href := exportUrl,
        ^.target := "_blank",
        ^.id := s"export-day-${exportType.toUrlString}",
        ^.onClick --> {
          Callback(GoogleEventTracker.sendEvent(terminalName, s"Export ${exportType.linkLabel}", exportDay.toISODateOnly))
        }
      )
    )
  }

  private def displayForRole(node: VdomNode, role: Role, loggedInUser: LoggedInUser): TagMod =
    if (loggedInUser.hasRole(role))
      node
    else
      EmptyVdom

  val component: Component[Props, State, Backend, CtorType.Props] = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialStateFromProps(p => State(p.terminalPageTab.subMode))
    .renderBackend[TerminalContentComponent.Backend]
    .build

  def apply(props: Props): VdomElement = component(props)
}
