package spatutorial.client.modules

import diode.react.ModelProxy
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import spatutorial.client.SPAMain._
import spatutorial.client.components.Bootstrap.CommonStyle
import spatutorial.client.components.Icon._
import spatutorial.client.components._
import spatutorial.client.services._

import scalacss.ScalaCssReact._
import spatutorial.client.logger._

object MainMenu {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class Props(router: RouterCtl[Loc], currentLoc: Loc)

  private case class MenuItem(idx: Int, label: (Props) => ReactNode, icon: Icon, location: Loc)

  // build the Todo menu item, showing the number of open todos
  private def buildTodoMenu(props: Props): ReactElement = {
    <.span(
      <.span("User Desk Overrides"),
      <.span(bss.labelOpt(CommonStyle.danger), bss.labelAsBadge)
    )
  }

  val terminalA1 = "A1"
  private val menuItems = Seq(
    MenuItem(1, _ => "Dashboard", Icon.dashboard, DashboardLoc),
    MenuItem(2, _ => "Flights", Icon.plane, FlightsLoc),
    MenuItem(3, buildTodoMenu, Icon.calculator, UserDeskRecommendationsLoc),
    MenuItem(4, _ => terminalA1, Icon.calculator, TerminalUserDeskRecommendationsLoc(terminalA1))
  )

  private class Backend($: BackendScope[Props, Unit]) {
    //    def mounted(props: Props) =
    //      // dispatch a message to refresh the todos
    //      Callback.when(propsops.proxy.value.isEmpty)(props.proxy.dispatch(RefreshTodos))

    def render(props: Props) = {
      <.ul(bss.navbar)(
        // build a list of menu items
        for (item <- menuItems) yield {
          <.li(^.key := item.idx, (props.currentLoc == item.location) ?= (^.className := "active"),
            props.router.link(item.location)(item.icon, " ", item.label(props))
          )
        }
      )
    }
  }

  private val component = ReactComponentB[Props]("MainMenu")
    .renderBackend[Backend]
    //    .componentDidMount(scope => scope.backend.mounted(scope.props))
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Loc): ReactElement =
    component(Props(ctl, currentLoc))
}
