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

object TerminalPage {

  case class Props(routeData: TerminalLoc, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    import TerminalHeatmaps._
    def render(props: Props) = {
      <.div(
//        heatmapOfDeskRecs(),
//        heatmapOfDeskRecsVsActualDesks(),
//        heatmapOfWaittimes(),
//        heatmapOfWorkloads(),
        <.div(
          ^.className := "terminal-desk-recs-container",
          TableTerminalDeskRecs.buildTerminalUserDeskRecsComponent(props.routeData.id)
        )
      )
    }
  }
  def apply(terminal: TerminalLoc, ctl: RouterCtl[Loc]): ReactElement =
    component(Props(terminal, ctl))

  private val component = ReactComponentB[Props]("Product")
    .renderBackend[Backend]
    .build
}
