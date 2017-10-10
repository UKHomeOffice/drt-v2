package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.ModelProxy
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.logger.log
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, TimeRangeHours}
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.TerminalName
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^.{<, VdomAttr, VdomElement, ^, vdomElementFromComponent, vdomElementFromTag, _}
import japgolly.scalajs.react.{BackendScope, Callback, ScalaComponent}
import org.scalajs.dom

import scala.util.Try

object TerminalContentComponent {

  case class Props(
                    crunchStatePot: Pot[CrunchState],
                    airportConfig: AirportConfig,
                    terminalName: TerminalName,
                    airportInfoPot: Pot[AirportInfo],
                    timeRangeHours: TimeRangeHours,
                    dayToDisplay: () => SDateLike
                  ) {
    lazy val hash = {
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

  def filterFlightsByRange(date: SDateLike, range: TimeRangeHours, arrivals: List[ApiFlightWithSplits]) = arrivals.filter(a => {

    def withinRange(ds: String) = if (ds.length > 0) SDate.parse(ds) match {
      case s: SDateLike if s.ddMMyyString == date.ddMMyyString =>
        s.getHours >= range.start && s.getHours < range.end
      case _ => false
    } else false

    withinRange(a.apiFlight.SchDT) || withinRange(a.apiFlight.EstDT) || withinRange(a.apiFlight.ActDT) || withinRange(a.apiFlight.EstChoxDT) || withinRange(a.apiFlight.ActChoxDT) || withinRange(SDate(MilliDate(a.apiFlight.PcpTime)).toISOString)
  })

  val timelineComp: Option[(Arrival) => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)

  def airportWrapper(portCode: String) = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

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

    def render(props: Props, state: State) = {
      val bestPax = ArrivalHelper.bestPax _
      val queueOrder = props.airportConfig.queueOrder

      <.div(
        <.a("Export Arrivals", ^.className := "btn btn-link", ^.href := s"${dom.window.location.pathname}/export/arrivals/${props.dayToDisplay().millisSinceEpoch}/${props.terminalName}", ^.target := "_blank"),
        <.a("Export Desks", ^.className := "btn btn-link", ^.href := s"${dom.window.location.pathname}/export/desks/${props.dayToDisplay().millisSinceEpoch}/${props.terminalName}", ^.target := "_blank"),
        TimeRangeFilter(TimeRangeFilter.Props(TimeRangeHours())),
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals"), ^.onClick --> t.modState(_ => State("arrivals"))),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#desksAndQueues", "Desks & Queues"), ^.onClick --> t.modState(_ => State("desksAndQueues"))),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#staffing", "Staffing"), ^.onClick --> t.modState(_ => State("staffing")))
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
            if (state.activeTab == "arrivals") {

              <.div(props.crunchStatePot.renderReady((crunchState: CrunchState) => {
                val flightsWithSplits = crunchState.flights
                val terminalFlights = flightsWithSplits.filter(f => f.apiFlight.Terminal == props.terminalName)
                val flightsInRange = filterFlightsByRange(props.dayToDisplay(), props.timeRangeHours, terminalFlights.toList)

                arrivalsTableComponent(FlightsWithSplitsTable.Props(flightsInRange, bestPax, queueOrder))
              }))
            } else ""
          }),
          <.div(^.id := "desksAndQueues", ^.className := "tab-pane fade terminal-desk-recs-container",
            if (state.activeTab == "desksAndQueues") {
              props.crunchStatePot.renderReady(crunchState => {

                TerminalDesksAndQueues(TerminalDesksAndQueues.Props(crunchState, props.airportConfig, props.terminalName))
              })
            } else ""
          ),
          <.div(^.id := "staffing", ^.className := "tab-pane fade terminal-staffing-container",
            if (state.activeTab == "staffing") {
              TerminalStaffing(TerminalStaffing.Props(props.terminalName))
            } else ""
          )))

    }
  }

  implicit val propsReuse = Reusability.by((_: Props).hash)
  implicit val stateReuse = Reusability.caseClass[State]

  val component = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialState(State("arrivals"))
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
