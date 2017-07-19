package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.ModelProxy
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.components.Heatmap.Series
import drt.client.components.TerminalHeatmaps._
import drt.client.logger.log
import drt.client.services.SPACircuit
import drt.shared._
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.Simulations.QueueSimulationResult
import japgolly.scalajs.react.component.{Generic, Js, Scala}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.{BackendScope, Callback, CallbackTo, CtorType, ScalaComponent}
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._

import scala.util.Try

object TerminalComponent {

  case class Props(terminalName: TerminalName)

  case class TerminalModel(
                            airportConfig: Pot[AirportConfig],
                            airportInfos: Pot[AirportInfo],
                            simulationResult: Map[QueueName, QueueSimulationResult],
                            flightsWithSplitsPot: Pot[FlightsWithSplits])

  def render(props: Props) = {
    val modelRCP = SPACircuit.connect(model => TerminalModel(
      model.airportConfig,
      model.airportInfos.getOrElse(props.terminalName, Pending()),
      model.simulationResult.getOrElse(props.terminalName, Map()),
      model.flightsWithSplitsPot
    ))

    modelRCP(modelMP => {
      val model = modelMP.value
      <.div(
        model.airportConfig.renderReady(airportConfig => {
          val heatmapProps = HeatmapComponent.Props(
            props.terminalName,
            model.simulationResult)

          val terminalContentProps = TerminalContentComponent.Props(
            airportConfig.portCode,
            props.terminalName,
            airportConfig.queueOrder,
            model.airportInfos,
            model.flightsWithSplitsPot)

          <.div(
            HeatmapComponent(heatmapProps),
            TerminalContentComponent(terminalContentProps)
          )
        }
        )
      )
    })
  }

  implicit val propsReuse = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("Terminal")
    .renderPS(($, props, state) => render(props))
    .build

  def apply(props: Props): VdomElement = component(props)
}

object HeatmapComponent {

  case class Props(
                    terminalName: TerminalName,
                    simulationResults: Map[QueueName, QueueSimulationResult]
                  )

  def render(props: Props) = {
    <.div({
      val seriesPot: Pot[List[Series]] = waitTimes(props.simulationResults, props.terminalName)
      <.div(
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#deskrecs", "Desk recommendations")),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#workloads", "Workloads")),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#paxloads", "Paxloads")),
          <.li(seriesPot.renderReady(s => {
            <.a(VdomAttr("data-toggle") := "tab", ^.href := "#waits", "Wait times")
          }))
        )
        ,
        <.div(^.className := "tab-content",
          <.div(^.id := "deskrecs", ^.className := "tab-pane fade in active",
            heatmapOfStaffDeploymentDeskRecs(props.terminalName)),
          <.div(^.id := "workloads", ^.className := "tab-pane fade",
            heatmapOfWorkloads(props.terminalName)),
          <.div(^.id := "paxloads", ^.className := "tab-pane fade",
            heatmapOfPaxloads(props.terminalName)),
          <.div(^.id := "waits", ^.className := "tab-pane fade",
            heatmapOfWaittimes(props.terminalName, props.simulationResults))
        ))
    })
  }

  implicit val deskRecReuse = Reusability.caseClass[DeskRec]
  implicit val queueSimulationResultReuse = Reusability.caseClass[QueueSimulationResult]
  implicit val queueNameToQueueSimulationResultReuse = Reusability.map[QueueName, QueueSimulationResult]
  implicit val propsReuse = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("Heatmaps")
    .render_P(render)
    .configure(Reusability.shouldComponentUpdateWithOverlay)
    .build

  def apply(props: Props): VdomElement = component(props)
}

object TerminalContentComponent {

  case class Props(
                    portCode: String,
                    terminalName: TerminalName,
                    queueOrder: List[PaxTypeAndQueue],
                    airportInfoPot: Pot[AirportInfo],
                    flightsWithSplitsPot: Pot[FlightsWithSplits]
                  )

  val timelineComp: Option[(Arrival) => html_<^.VdomElement] = Some(FlightTableComponents.timelineCompFunc _)

  def airportWrapper(portCode: String) = SPACircuit.connect(_.airportInfos.getOrElse(portCode, Pending()))

  def originMapper(portCode: String): VdomElement = {
    Try {
      vdomElementFromComponent(airportWrapper(portCode) { (proxy: ModelProxy[Pot[AirportInfo]]) =>
        <.span(
          proxy().render(ai => <.span(^.title := s"${ai.airportName}, ${ai.city}, ${ai.country}", portCode)),
          proxy().renderEmpty(<.span(portCode)),
          proxy().renderPending((n) => <.span(portCode)))
      })
    }.recover {
      case e =>
        log.error(s"origin mapper error $e")
        vdomElementFromTag(<.div(portCode))
    }.get
  }

  case class State(activeTab: String)

  class Backend(t: BackendScope[Props, State]) {
    val x = FlightsWithSplitsTable.ArrivalsTable(
      timelineComp,
      originMapper,
      splitsGraphComponentColoured)(paxComp(843))


    def render(props: Props, state: State) = {
      val bestPax = BestPax(props.portCode)
      val queueOrder = props.queueOrder

      <.div(
        <.ul(^.className := "nav nav-tabs",
          <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals"), ^.onClick --> t.modState(_ => State("arrivals"))),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#queues", "Desks & Queues"), ^.onClick --> t.modState(_ => State("queues"))),
          <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#staffing", "Staffing"), ^.onClick --> t.modState(_ => State("staffing")))
        ),
        <.div(^.className := "tab-content",
          <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
            if (state.activeTab == "arrivals") {
              val flights: Pot[FlightsApi.FlightsWithSplits] = props.flightsWithSplitsPot

              <.div(flights.renderReady((flightsWithSplits: FlightsWithSplits) => {
                val maxFlightPax = flightsWithSplits.flights.map(_.apiFlight.MaxPax).max
                val flightsForTerminal = FlightsWithSplits(flightsWithSplits.flights.filter(f => f.apiFlight.Terminal == props.terminalName))
                x(FlightsWithSplitsTable.Props(flightsForTerminal, bestPax, queueOrder))
              }))
            } else ""
          }),
          <.div(^.id := "queues", ^.className := "tab-pane fade terminal-desk-recs-container",
            if (state.activeTab == "queues") {
              TerminalDeploymentsTable.terminalDeploymentsComponent(TerminalDeploymentsTable.TerminalProps(props.terminalName, props.flightsWithSplitsPot))
            } else ""
          ),
          <.div(^.id := "staffing", ^.className := "tab-pane fade terminal-staffing-container",
            if (state.activeTab == "staffing") {
              TerminalStaffing(TerminalStaffing.Props(props.terminalName))
            } else ""
          )))

    }
  }

  implicit val airportInfoReuse = Reusability.always[Pot[AirportInfo]]
  implicit val flightsWithSplitsReuse =
    Reusability.by((flightsPot: Pot[FlightsWithSplits]) => {
      flightsPot.toOption.map(_.flights.map(f => {
        (f.splits.hashCode(),
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
    })
  implicit val paxTypeAndQueueReuse = Reusability.always[PaxTypeAndQueue]
  implicit val propsReuse = Reusability.caseClass[Props]
  implicit val stateReuse  = Reusability.caseClass[State]

  val component = ScalaComponent.builder[Props]("TerminalContentComponent")
    .initialState(State("arrivals"))
    .renderBackend[TerminalContentComponent.Backend]
    .componentDidMount((p) => Callback.log(s"terminal component didMount"))
    .configure(Reusability.shouldComponentUpdateWithOverlay)
    .build

  def apply(props: Props): VdomElement = {
    component(props)
  }
}
