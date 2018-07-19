package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.{ModelProxy, ReactConnectProxy}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.{CrunchState, MillisSinceEpoch}
import drt.shared.FlightsApi.TerminalName
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

  case class Props(
                    crunchStatePot: Pot[CrunchState],
                    potShifts: Pot[String],
                    potFixedPoints: Pot[String],
                    potStaffMovements: Pot[Seq[StaffMovement]],
                    airportConfig: AirportConfig,
                    terminalPageTab: TerminalPageTabLoc,
                    airportInfoPot: Pot[AirportInfo],
                    defaultTimeRangeHours: TimeRangeHours,
                    router: RouterCtl[Loc],
                    showActuals: Boolean,
                    viewMode: ViewMode,
                    roles: Pot[List[String]],
                    minuteTicker: Int
                  ) {
    lazy val hash: (Int, Int) = {
      val depsHash = crunchStatePot.map(
        cs => (cs.crunchMinutes, cs.staffMinutes, cs.flights).hashCode()
      ).getOrElse(0)

      (depsHash, minuteTicker)
    }
  }

  case class State(activeTab: String, showExportDialogue: Boolean = false)

  implicit val propsReuse: Reusability[Props] = Reusability.by((_: Props).hash)
  implicit val stateReuse: Reusability[State] = Reusability.derive[State]

  def filterCrunchStateByRange(day: SDateLike,
                               range: TimeRangeHours,
                               state: CrunchState,
                               terminalName: TerminalName): CrunchState = {
    val startOfDay = SDate(day.getFullYear(), day.getMonth(), day.getDate())
    val startOfView = startOfDay.addHours(range.start)
    val endOfView = startOfDay.addHours(range.end)
    state.window(startOfView, endOfView, terminalName)
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

      <.div(^.className := s"view-mode-content $viewModeStr",
        <.div(^.className := "tabs-with-export",
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := desksAndQueuesActive, <.a(VdomAttr("data-toggle") := "tab", "Desks & Queues"), ^.onClick --> {
              props.router.set(props.terminalPageTab.copy(subMode = "desksAndQueues"))
            }),
            <.li(^.className := arrivalsActive, <.a(VdomAttr("data-toggle") := "tab", "Arrivals"), ^.onClick --> {
              props.router.set(props.terminalPageTab.copy(subMode = "arrivals"))
            }),
            <.li(^.className := staffingActive, <.a(VdomAttr("data-toggle") := "tab", "Staff Movements"), ^.onClick --> {
              props.router.set(props.terminalPageTab.copy(subMode = "staffing"))
            })
          ),
          <.div(^.className := "exports",
            <.a("Export Arrivals",
              ^.className := "btn btn-default",
              ^.href := s"${dom.window.location.pathname}/export/arrivals/${props.terminalPageTab.viewMode.millis}/${props.terminalPageTab.terminal}?startHour=${timeRangeHours.start}&endHour=${timeRangeHours.end}",
              ^.target := "_blank"
            ),
            <.a(
              "Export Desks",
              ^.className := "btn btn-default",
              ^.href := s"${dom.window.location.pathname}/export/desks/${props.terminalPageTab.viewMode.millis}/${props.terminalPageTab.terminal}?startHour=${timeRangeHours.start}&endHour=${timeRangeHours.end}",
              ^.target := "_blank"
            ),
            MultiDayExportComponent(props.terminalPageTab.terminal, props.terminalPageTab.dateFromUrlOrNow)
          )
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "desksAndQueues", ^.className := s"tab-pane terminal-desk-recs-container $desksAndQueuesPanelActive",
            if (state.activeTab == "desksAndQueues") {
              log.info(s"Rendering desks and queue")
              props.crunchStatePot.render(crunchState => {
                val filteredPortState = filterCrunchStateByRange(props.terminalPageTab.viewMode.time, timeRangeHours, crunchState, props.terminalPageTab.terminal)
                TerminalDesksAndQueues(
                  TerminalDesksAndQueues.Props(
                    filteredPortState,
                    props.airportConfig,
                    props.terminalPageTab.terminal,
                    props.showActuals,
                    props.viewMode
                  )
                )
              })
            } else ""
          ),
          <.div(^.id := "arrivals", ^.className := s"tab-pane in $arrivalsPanelActive", {
            if (state.activeTab == "arrivals") {
              <.div(props.crunchStatePot.render((crunchState: CrunchState) => {
                val filteredPortState = filterCrunchStateByRange(props.terminalPageTab.viewMode.time, timeRangeHours, crunchState, props.terminalPageTab.terminal)
                arrivalsTableComponent(FlightsWithSplitsTable.Props(filteredPortState.flights.toList, queueOrder, props.airportConfig.hasEstChox))
              }))
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
                props.roles,
                props.viewMode
              ))
            } else ""
          ))
      )
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
    .componentDidMount(_ => Callback.log(s"terminal component didMount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}
