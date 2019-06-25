package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.{ModelProxy, ReactConnectProxy}
import drt.client.SPAMain
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.logger.log
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.PortState
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, VdomAttr, VdomElement, ^, vdomElementFromComponent, vdomElementFromTag, _}
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.{BackendScope, Callback, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.html.Div

import scala.util.Try

object TerminalContentComponent {

  case class Props(crunchStatePot: Pot[PortState],
                   potShifts: Pot[ShiftAssignments],
                   potFixedPoints: Pot[FixedPointAssignments],
                   potStaffMovements: Pot[Seq[StaffMovement]],
                   airportConfig: AirportConfig,
                   terminalPageTab: TerminalPageTabLoc,
                   defaultTimeRangeHours: TimeRangeHours,
                   router: RouterCtl[Loc],
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUserPot: Pot[LoggedInUser],
                   minuteTicker: Int)

  case class State(activeTab: String, showExportDialogue: Boolean = false)

  def filterCrunchStateByRange(day: SDateLike,
                               range: TimeRangeHours,
                               state: PortState,
                               queues: Map[TerminalName, Seq[QueueName]]): PortState = {
    val startOfDay = SDate(day.getFullYear(), day.getMonth(), day.getDate())
    val startOfView = startOfDay.addHours(range.start)
    val endOfView = startOfDay.addHours(range.end)
    state.window(startOfView, endOfView, queues)
  }

  def viewStartAndEnd(day: SDateLike, range: TimeRangeHours): (SDateLike, SDateLike) = {
    val startOfDay = SDate(day.getFullYear(), day.getMonth(), day.getDate())
    val startOfView = startOfDay.addHours(range.start)
    val endOfView = startOfDay.addHours(range.end)
    (startOfView, endOfView)
  }

  val timelineComp: Option[Arrival => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)

  def airportWrapper(portCode: String): ReactConnectProxy[Pot[AirportInfo]] = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  def originMapper(portCode: String): VdomElement = {
    Try {
      vdomElementFromComponent(airportWrapper(portCode) { proxy: ModelProxy[Pot[AirportInfo]] =>
        <.span(
          proxy().render(ai => <.span(^.title := s"${ai.airportName}, ${ai.city}, ${ai.country}", portCode)),
          proxy().renderEmpty(<.span(portCode))
        )
      })
    }.recover {
      case e =>
        log.error(s"origin mapper error $e")
        vdomElementFromTag(<.div(portCode))
    }.get
  }

  class Backend(t: BackendScope[Props, State]) {
    val arrivalsTableComponent = FlightsWithSplitsTable.ArrivalsTable(
      None,
      originMapper,
      splitsGraphComponentColoured)(paxComp(843))

    def render(props: Props, state: State): TagOf[Div] = {
      val queueOrder = props.airportConfig.queueOrder

      val desksAndQueuesActive = if (state.activeTab == "desksAndQueues") "active" else ""
      val arrivalsActive = if (state.activeTab == "arrivals") "active" else ""
      val staffingActive = if (state.activeTab == "staffing") "active" else ""

      val desksAndQueuesPanelActive = if (state.activeTab == "desksAndQueues") "active" else "fade"
      val arrivalsPanelActive = if (state.activeTab == "arrivals") "active" else "fade"
      val staffingPanelActive = if (state.activeTab == "staffing") "active" else "fade"
      val viewModeStr = props.terminalPageTab.viewMode.getClass.getSimpleName.toLowerCase

      val timeRangeHours: CustomWindow = timeRange(props)

      <.div(
        props.crunchStatePot.renderPending(_ => if (props.crunchStatePot.isEmpty) <.div(^.id := "terminal-spinner", Icon.spinner) else ""),
        props.crunchStatePot.renderEmpty(if (!props.crunchStatePot.isPending) {
          <.div(^.id := "terminal-data", "Nothing to show for this time period")
        } else ""),
        props.crunchStatePot.render((crunchState: PortState) => {
          val queues = props.airportConfig.queues.filterKeys(_ == props.terminalPageTab.terminal)
          val (viewStart, viewEnd) = viewStartAndEnd(props.terminalPageTab.viewMode.time, timeRangeHours)
          val filteredPortState = crunchState.window(viewStart, viewEnd, queues)
          <.div(^.className := s"view-mode-content $viewModeStr",
            <.div(^.className := "tabs-with-export",
              <.ul(^.className := "nav nav-tabs",
                <.li(^.className := desksAndQueuesActive,
                  <.a(^.id := "desksAndQueuesTab", VdomAttr("data-toggle") := "tab", "Desks & Queues"), ^.onClick --> {
                    GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Desks & Queues", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                    props.router.set(props.terminalPageTab.copy(subMode = "desksAndQueues"))
                  }),
                <.li(^.className := arrivalsActive,
                  <.a(^.id := "arrivalsTab", VdomAttr("data-toggle") := "tab", "Arrivals"), ^.onClick --> {
                    GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Arrivals", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                    props.router.set(props.terminalPageTab.copy(subMode = "arrivals"))
                  }),
                <.li(^.className := staffingActive,
                  <.a(^.id := "staffMovementsTab", VdomAttr("data-toggle") := "tab", "Staff Movements"), ^.onClick --> {
                    GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Staff Movements", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly)
                    props.router.set(props.terminalPageTab.copy(subMode = "staffing"))
                  })
              ),
              <.div(^.className := "exports",
                <.a("Export Arrivals",
                  ^.className := "btn btn-default",
                  ^.href := SPAMain.absoluteUrl(s"export/arrivals/${props.terminalPageTab.viewMode.millis}/${props.terminalPageTab.terminal}?startHour=${timeRangeHours.start}&endHour=${timeRangeHours.end}"),
                  ^.target := "_blank",
                  ^.onClick --> {
                    Callback(GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Export Arrivals", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly))
                  }
                ),
                <.a(
                  "Export Desks",
                  ^.className := "btn btn-default",
                  ^.href := SPAMain.absoluteUrl(s"export/desks/${props.terminalPageTab.viewMode.millis}/${props.terminalPageTab.terminal}?startHour=${timeRangeHours.start}&endHour=${timeRangeHours.end}"),
                  ^.target := "_blank",
                  ^.onClick --> {
                    Callback(GoogleEventTracker.sendEvent(props.terminalPageTab.terminal, "Export Desks", props.terminalPageTab.dateFromUrlOrNow.toISODateOnly))
                  }
                ),
                props.loggedInUserPot.render(loggedInUser => {
                  MultiDayExportComponent(props.terminalPageTab.terminal, props.terminalPageTab.dateFromUrlOrNow, loggedInUser)
                })
              )
            ),
            <.div(^.className := "tab-content",
              <.div(^.id := "desksAndQueues", ^.className := s"tab-pane terminal-desk-recs-container $desksAndQueuesPanelActive",
                if (state.activeTab == "desksAndQueues") {
                  props.loggedInUserPot.render(loggedInUser => {
                    val (viewStart, _) = viewStartAndEnd(props.terminalPageTab.viewMode.time, timeRangeHours)
                    TerminalDesksAndQueues(
                      TerminalDesksAndQueues.Props(
                        props.router,
                        filteredPortState,
                        viewStart,
                        timeRangeHours.end - timeRangeHours.start,
                        props.airportConfig,
                        props.terminalPageTab,
                        props.showActuals,
                        props.viewMode,
                        loggedInUser
                      )
                    )
                  })
                } else ""
              ),
              <.div(^.id := "arrivals", ^.className := s"tab-pane in $arrivalsPanelActive", {
                if (state.activeTab == "arrivals") {
                  val flightsForTerminal = filteredPortState.flights.values.filter(_.apiFlight.Terminal == props.terminalPageTab.terminal).toList
                  arrivalsTableComponent(FlightsWithSplitsTable.Props(flightsForTerminal, queueOrder, props.airportConfig.hasEstChox))
                } else ""
              }),
              <.div(^.id := "available-staff", ^.className := s"tab-pane terminal-staffing-container $staffingPanelActive",
                if (state.activeTab == "staffing") {
                  TerminalStaffing(TerminalStaffing.Props(
                    props.terminalPageTab.terminal,
                    props.potShifts,
                    props.potFixedPoints,
                    props.potStaffMovements,
                    props.airportConfig,
                    props.loggedInUserPot,
                    props.viewMode
                  ))
                } else ""
              ))
          )
        }))
    }

  }

  def timeRange(props: Props): CustomWindow = {
    TimeRangeHours(
      props.terminalPageTab.timeRangeStart.getOrElse(props.defaultTimeRangeHours.start),
      props.terminalPageTab.timeRangeEnd.getOrElse(props.defaultTimeRangeHours.end)
    )
  }

  val component = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialStateFromProps(p => State(p.terminalPageTab.subMode))
    .renderBackend[TerminalContentComponent.Backend]
    .componentDidMount(p =>
      Callback {
        val page = s"${p.props.terminalPageTab.terminal}/${p.props.terminalPageTab.mode}/${p.props.terminalPageTab.subMode}"
        val pageWithTime = s"$page/${timeRange(p.props).start}/${timeRange(p.props).end}"
        val pageWithDate = p.props.terminalPageTab.date.map(s => s"$page/${p.props.terminalPageTab.parseDateString(s)}/${timeRange(p.props).start}/${timeRange(p.props).end}").getOrElse(pageWithTime)
        GoogleEventTracker.sendPageView(pageWithDate)
        log.info("terminal component didMount")
      }
    )
    .build

  def apply(props: Props): VdomElement = component(props)
}
