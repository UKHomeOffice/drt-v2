package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.{ModelProxy, ReactConnectProxy}
import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, TimeRangeHours}
import drt.shared.CrunchApi.CrunchState
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import japgolly.scalajs.react.vdom.html_<^.{<, VdomAttr, VdomElement, ^, vdomElementFromComponent, vdomElementFromTag, _}
import japgolly.scalajs.react.{BackendScope, Callback, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.html.Div

import scala.util.Try

object TerminalContentComponent {

  case class Props(
                    crunchStatePot: Pot[CrunchState],
                    airportConfig: AirportConfig,
                    terminalPageTab: TerminalPageTabLoc,
                    airportInfoPot: Pot[AirportInfo],
                    timeRangeHours: TimeRangeHours,
                    router: RouterCtl[Loc]
                  ) {
    lazy val hash: (String, Option[List[(Int, String, String, String, String, String, String, String, String, Long, Int)]], Int, Int) = {
      val depsHash = crunchStatePot.map(
        cs => cs.crunchMinutes.toSeq.map(_.hashCode())
      ).toList.mkString("|")

      val flightsHash: Option[List[(Int, String, String, String, String, String, String, String, String, Long, Int)]] = crunchStatePot.toOption.map(_.flights.toList.map(f => {
        (f.splits.hashCode,
          f.apiFlight.Status,
          f.apiFlight.Gate,
          f.apiFlight.Stand,
          f.apiFlight.SchDT,
          f.apiFlight.EstDT,
          f.apiFlight.ActDT,
          f.apiFlight.EstChoxDT,
          f.apiFlight.ActChoxDT,
          f.apiFlight.PcpTime,
          f.apiFlight.ActPax
        )
      }))

      (depsHash, flightsHash, timeRangeHours.start, timeRangeHours.end)
    }
  }

  def filterFlightsByRange(date: SDateLike, range: TimeRangeHours, arrivals: List[ApiFlightWithSplits]): List[ApiFlightWithSplits] = arrivals.filter(a => {

    def withinRange(ds: String) = if (ds.length > 0) SDate.parse(ds) match {
      case s: SDateLike if s.ddMMyyString == date.ddMMyyString =>
        s.getHours >= range.start && s.getHours < range.end
      case _ => false
    } else false

    withinRange(SDate(MilliDate(a.apiFlight.PcpTime)).toISOString())
  })

  val timelineComp: Option[(Arrival) => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)

  def airportWrapper(portCode: String): ReactConnectProxy[Pot[AirportInfo]] = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  def originMapper(portCode: String): VdomElement = {
    Try {
      vdomElementFromComponent(airportWrapper(portCode) { (proxy: ModelProxy[Pot[AirportInfo]]) =>
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

  case class State(activeTab: String)

  class Backend(t: BackendScope[Props, State]) {
    val arrivalsTableComponent = FlightsWithSplitsTable.ArrivalsTable(
      timelineComp,
      originMapper,
      splitsGraphComponentColoured)(paxComp(843))

    def render(props: Props, state: State): TagOf[Div] = {
      val bestPax = ArrivalHelper.bestPax _
      val queueOrder = props.airportConfig.queueOrder

      val arrivalsActive = if (state.activeTab == "arrivals") "active" else ""
      val desksAndQueuesActive = if (state.activeTab == "desksAndQueues") "active" else ""
      val staffingActive = if (state.activeTab == "staffing") "active" else ""

      val arrivalsPanelActive = if (state.activeTab == "arrivals") "active" else "fade"
      val desksAndQueuesPanelActive = if (state.activeTab == "desksAndQueues") "active" else "fade"
      val staffingPanelActive = if (state.activeTab == "staffing") "active" else "fade"

      <.div(
        <.a("Export Arrivals", ^.className := "btn btn-link", ^.href := s"${dom.window.location.pathname}/export/arrivals/${props.terminalPageTab.viewMode.millis}/${props.terminalPageTab.terminal}", ^.target := "_blank"),
        <.a("Export Desks", ^.className := "btn btn-link", ^.href := s"${dom.window.location.pathname}/export/desks/${props.terminalPageTab.viewMode.millis}/${props.terminalPageTab.terminal}", ^.target := "_blank"),
        TimeRangeFilter(TimeRangeFilter.Props(TimeRangeHours())),
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := arrivalsActive, <.a(VdomAttr("data-toggle") := "tab", "Arrivals"), ^.onClick --> {
            props.router.set(props.terminalPageTab.copy(tab = "arrivals"))
          }),
          <.li(^.className := desksAndQueuesActive, <.a(VdomAttr("data-toggle") := "tab", "Desks & Queues"), ^.onClick --> {
            props.router.set(props.terminalPageTab.copy(tab = "desksAndQueues"))
          }),
          <.li(^.className := staffingActive, <.a(VdomAttr("data-toggle") := "tab", "Staffing"), ^.onClick --> {
            props.router.set(props.terminalPageTab.copy(tab = "staffing"))
          })
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "arrivals", ^.className := s"tab-pane in $arrivalsPanelActive", {
            if (state.activeTab == "arrivals") {
              log.info(s"Rendering arrivals $state")

              <.div(props.crunchStatePot.renderReady((crunchState: CrunchState) => {
                val flightsWithSplits = crunchState.flights
                val terminalFlights = flightsWithSplits.filter(f => f.apiFlight.Terminal == props.terminalPageTab.terminal)
                val flightsInRange = filterFlightsByRange(props.terminalPageTab.viewMode.time, props.timeRangeHours, terminalFlights.toList)

                arrivalsTableComponent(FlightsWithSplitsTable.Props(flightsInRange, bestPax, queueOrder))
              }))
            } else ""
          }),
          <.div(^.id := "desksAndQueues", ^.className := s"tab-pane terminal-desk-recs-container $desksAndQueuesPanelActive", ^.href := "#desksAndQueues",
            if (state.activeTab == "desksAndQueues") {
              log.info(s"Rendering desks and queue $state")
              props.crunchStatePot.renderReady(crunchState => {
                log.info(s"rendering ready d and q")
                TerminalDesksAndQueues(TerminalDesksAndQueues.Props(crunchState, props.airportConfig, props.terminalPageTab.terminal))
              })
            } else ""
          ),
          <.div(^.id := "staffing", ^.className := s"tab-pane terminal-staffing-container $staffingPanelActive", ^.href := "#staffing",

            if (state.activeTab == "staffing") {
              log.info(s"Rendering staffing $state")
              TerminalStaffing(TerminalStaffing.Props(props.terminalPageTab.terminal))
            } else ""
          )))
    }
  }

  implicit val propsReuse: Reusability[Props] = Reusability.by((_: Props).hash)
  implicit val stateReuse: Reusability[State] = Reusability.caseClass[State]

  val component = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialStateFromProps(p => State(p.terminalPageTab.tab))
    .renderBackend[TerminalContentComponent.Backend]
    .componentDidMount((p) => {
      Callback.log(s"terminal component didMount")
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}
