package drt.client.components

import diode.data.{Pending, Pot}
import diode.react.ModelProxy
import drt.client.components.FlightComponents.SplitsGraph.splitsGraphComponentColoured
import drt.client.components.FlightComponents.paxComp
import drt.client.components.Heatmap.Series
import drt.client.components.TerminalComponent.{Props, render}
import drt.client.components.TerminalHeatmaps._
import drt.client.logger.log
import drt.client.services.SPACircuit
import drt.shared._
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.Simulations.QueueSimulationResult
import japgolly.scalajs.react.{Callback, ScalaComponent}
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._

import scala.util.Try

object TerminalComponent {

  case class Props(terminalName: TerminalName)

  case class TerminalModel(
                            airportConfig: Pot[AirportConfig],
                            airportInfos: Pot[AirportInfo],
                            //                            simulationResult: Map[QueueName, QueueSimulationResult],
                            flightsWithSplitsPot: Pot[FlightsWithSplits])

  def render(props: Props) = {
    val modelRCP = SPACircuit.connect(model => TerminalModel(
      model.airportConfig,
      model.airportInfos.getOrElse(props.terminalName, Pending()),
      //      model.simulationResult.getOrElse(props.terminalName, Map()),
      model.flightsWithSplitsPot
    ))
    
    modelRCP(modelMP => {
      val model = modelMP.value
      <.div(
        model.airportConfig.renderReady(airportConfig => {
          //          val heatmapProps = HeatmapComponent.Props(
          //            props.terminalName,
          //            model.simulationResult)

          val terminalContentProps = TerminalContentComponent.Props(
            airportConfig.portCode,
            props.terminalName,
            airportConfig.queueOrder,
            model.airportInfos,
            model.flightsWithSplitsPot)

          <.div(
            //            HeatmapComponent(heatmapProps),
            TerminalContentComponent(terminalContentProps)
          )
        }
        )
      )
    })
  }

  implicit val propsReuse = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("Terminal")
    .render_P(render)
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
    .configure(Reusability.shouldComponentUpdate)
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

  def originMapper(airportInfoPot: Pot[AirportInfo])(portCode: String): VdomElement = {
    Try {
      vdomElementFromTag(<.span(
        airportInfoPot.render(ai => <.span(^.title := s"${ai.airportName}, ${ai.city}, ${ai.country}", portCode)),
        airportInfoPot.renderEmpty(<.span(portCode)),
        airportInfoPot.renderPending((n) => <.span(portCode))))
    }.recover {
      case e =>
        log.error(s"origin mapper error $e")
        vdomElementFromTag(<.div(portCode))
    }.get
  }

  def render(props: Props) = {
    val bestPax = BestPax(props.portCode)
    val queueOrder = props.queueOrder

    <.div(
      <.ul(^.className := "nav nav-tabs",
        <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals")),
        <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#queues", "Desks & Queues")),
        <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#staffing", "Staffing"))
      )
      ,
      <.div(^.className := "tab-content",
        <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
          val flights: Pot[FlightsApi.FlightsWithSplits] = props.flightsWithSplitsPot

          <.div(flights.renderReady((flightsWithSplits: FlightsWithSplits) => {
            val maxFlightPax = flightsWithSplits.flights.map(_.apiFlight.MaxPax).max
            val flightsForTerminal = FlightsWithSplits(flightsWithSplits.flights.filter(f => f.apiFlight.Terminal == props.terminalName))

            FlightsWithSplitsTable.ArrivalsTable(
              timelineComp,
              originMapper(props.airportInfoPot),
              paxComp(maxFlightPax),
              splitsGraphComponentColoured)(FlightsWithSplitsTable.Props(flightsForTerminal, bestPax, queueOrder))
          }))
        }),
        <.div(^.id := "queues", ^.className := "tab-pane fade terminal-desk-recs-container",
          "" //TerminalDeploymentsTable.terminalDeploymentsComponent(TerminalDeploymentsTable.TerminalProps(props.terminalName))
        ),
        <.div(^.id := "staffing", ^.className := "tab-pane fade terminal-staffing-container",
          "" //TerminalStaffing(TerminalStaffing.Props(props.terminalName))
        )))
  }

  implicit val airportInfoReuse = Reusability.always[Pot[AirportInfo]]
//  implicit val flightsWithSplitsReuse = Reusability.never[Pot[FlightsWithSplits]]
  implicit val flightsWithSplitsReuse = Reusability.by((_: Pot[FlightsWithSplits]).isReady)
  implicit val paxTypeAndQueueReuse = Reusability.always[PaxTypeAndQueue]
  implicit val propsReuse = Reusability.caseClass[Props]

  val component = ScalaComponent.builder[Props]("TerminalContentComponent")
    .render_P(render)
    .componentDidMount((p) => Callback.log(s"terminal component didMount"))
    .configure(Reusability.shouldComponentUpdateWithOverlay)
    .build

  def apply(props: Props): VdomElement = component(props)
}