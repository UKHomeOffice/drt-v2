package spatutorial.client.components

import diode.data.{Pot, Ready}
import diode.react.{ModelProxy, ReactConnectProxy}
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import spatutorial.client.SPAMain.{Loc, TerminalLoc}
import spatutorial.client.components.Heatmap.Series
import spatutorial.client.services.{RootModel, SPACircuit}
import spatutorial.client.logger._
import spatutorial.shared.FlightsApi.TerminalName

object TerminalPage {

  case class Props(terminalName: TerminalName, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    import TerminalHeatmaps._
    def render(props: Props) = {
      <.div(
//        heatmapOfDeskRecs(),
//        heatmapOfDeskRecsVsActualDesks(),
        heatmapOfWaittimes(props.terminalName),
        heatmapOfWorkloads(props.terminalName),
        <.div(
          ^.className := "terminal-desk-recs-container",
          TableTerminalDeskRecs.buildTerminalUserDeskRecsComponent(props.terminalName)
        )
      )
    }
  }
  def apply(terminalName: TerminalName, ctl: RouterCtl[Loc]): ReactElement =
    component(Props(terminalName, ctl))

  private val component = ReactComponentB[Props]("Product")
    .renderBackend[Backend]
    .build
}
