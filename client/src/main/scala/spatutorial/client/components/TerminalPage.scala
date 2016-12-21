package spatutorial.client.components

import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import spatutorial.client.SPAMain.Loc
import spatutorial.shared.FlightsApi.TerminalName

object TerminalPage {

  case class Props(terminalName: TerminalName, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    import TerminalHeatmaps._
    def render(props: Props) = {
      <.div(
        heatmapOfDeskRecs(props.terminalName),
        heatmapOfDeskRecsVsActualDesks(props.terminalName),
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
