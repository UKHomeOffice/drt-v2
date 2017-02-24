package spatutorial.client.components

import diode.data.Pot
import diode.react.ModelProxy
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.vdom._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import spatutorial.client.SPAMain.Loc
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.logger._
import spatutorial.client.services.{SPACircuit, Workloads}
import spatutorial.shared.FlightsApi.TerminalName

object TerminalDeploymentsPage {

  case class Props(terminalName: TerminalName, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {

    import TerminalHeatmaps._

    def render(props: Props) = {


      val simulationResultRCP = SPACircuit.connect(_.simulationResult)
      simulationResultRCP(simulationResultMP => {
        val seriesPot: Pot[List[Series]] = waitTimes(simulationResultMP().getOrElse(props.terminalName, Map()), props.terminalName)
        <.div(
          <.ul(^.className := "nav nav-tabs",
            <.li(^.className := "active", <.a("data-toggle".reactAttr := "tab", ^.href := "#deskrecs", "Desk recommendations")),
            <.li(<.a("data-toggle".reactAttr := "tab", ^.href := "#workloads", "Workloads")),
            seriesPot.renderReady(s =>
              <.li(<.a("data-toggle".reactAttr := "tab", ^.href := "#waits", "Wait times"))
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
          <.div(
            ^.className := "terminal-desk-recs-container",
            TerminalDeploymentsTable.terminalDeploymentsComponent(props.terminalName)
          )
        )
      })
    }
  }

  def apply(terminalName: TerminalName, ctl: RouterCtl[Loc]): ReactElement =
    component(Props(terminalName, ctl))

  private val component = ReactComponentB[Props]("Product")
    .renderBackend[Backend]
    .build
}
