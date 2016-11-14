package spatutorial.client.components

import japgolly.scalajs.react.{BackendScope, ReactComponentB, ReactElement}
import japgolly.scalajs.react.extra.router.RouterCtl
import spatutorial.client.SPAMain.{Loc, TerminalLoc}

object TerminalPage {

  case class Props(routeData: TerminalLoc, ctl: RouterCtl[Loc])

  class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = TableTerminalDeskRecs.buildTerminalUserDeskRecsComponent(props.routeData.id)
  }

  def apply(terminal: TerminalLoc, ctl: RouterCtl[Loc]): ReactElement =
    component(Props(terminal, ctl))

  private val component = ReactComponentB[Props]("Product")
    .renderBackend[Backend]
    .build
}
