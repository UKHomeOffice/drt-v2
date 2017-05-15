package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._
import japgolly.scalajs.react.BackendScope
import drt.client.SPAMain.Loc
import drt.client.components.Heatmap.Series
import drt.client.logger._
import drt.client.modules.FlightsWithSplitsView
import drt.client.services.RootModel.TerminalQueueSimulationResults
import drt.client.services.{SPACircuit, Workloads}
import drt.shared.FlightsApi.TerminalName
import drt.shared.SimulationResult

object TerminalPage {

  case class Props(terminalName: TerminalName, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {

    import TerminalHeatmaps._

    def render(props: Props) = {


      val simulationResultRCP = SPACircuit.connect(_.simulationResult)
      simulationResultRCP((simulationResultMP )=> {
        val seriesPot: Pot[List[Series]] = waitTimes(simulationResultMP().getOrElse(props.terminalName, Map()), props.terminalName)
        <.div(
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#deskrecs", "Desk recommendations")),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#workloads", "Workloads")),
            seriesPot.renderReady(s =>
              <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#waits", "Wait times"))
            )
          ),
          <.div(^.className := "tab-content",
            <.div(^.id := "deskrecs", ^.className := "tab-pane fade in active",
              heatmapOfDeskRecs(props.terminalName)),
            <.div(^.id := "workloads", ^.className := "tab-pane fade",
              heatmapOfWorkloads(props.terminalName)),
            <.div(^.id := "waits", ^.className := "tab-pane fade",
              heatmapOfWaittimes(props.terminalName))
          ),
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a(VdomAttr("data-toggle") := "tab", ^.href := "#arrivals", "Arrivals")),
            <.li(<.a(VdomAttr("data-toggle") := "tab", ^.href := "#queues", "Desks & Queues"))
          ),
          <.div(^.className := "tab-content",
            <.div(^.id := "arrivals", ^.className := "tab-pane fade in active", {
              val airportWrapper = SPACircuit.connect(_.airportInfos)
              val flightsWrapper = SPACircuit.connect(_.flightsWithApiSplits(props.terminalName))
              airportWrapper(airportInfoProxy =>
                flightsWrapper(proxy =>
                  FlightsWithSplitsView(FlightsWithSplitsView.Props(proxy.value, airportInfoProxy.value))))
            }
            ),
            <.div(^.id := "queues", ^.className := "tab-pane fade terminal-desk-recs-container",
              TerminalDeploymentsTable.terminalDeploymentsComponent(props.terminalName)
            )
          )
        )
      })
    }
  }

  def apply(terminalName: TerminalName, ctl: RouterCtl[Loc]): VdomElement =
    component(Props(terminalName, ctl))

  private val component = ScalaComponent.builder[Props]("Product")
    .renderBackend[Backend]
    .build
}
