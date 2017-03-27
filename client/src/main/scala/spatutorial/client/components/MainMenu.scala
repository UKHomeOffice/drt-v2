package drt.client.components

import java.io.Serializable

import diode.data.{Pot, PotState}
import diode.data.PotState.PotReady
import diode.react.ModelProxy
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import drt.client.SPAMain._
import drt.client.components.Bootstrap.CommonStyle
import drt.client.components.Icon._
import drt.client.services.SPACircuit
import drt.shared.{HasAirportConfig, AirportConfig}
import drt.client.logger._

import scalacss.ScalaCssReact._

object MainMenu {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class Props(router: RouterCtl[Loc], currentLoc: Loc)

  case class MenuItem(idx: Int, label: (Props) => ReactNode, icon: Icon, location: Loc)

  // build the Todo menu item, showing the number of open todos
  private def buildTodoMenu(props: Props): ReactElement = {
    <.span(
      <.span("User Desk Overrides"),
      <.span(bss.labelOpt(CommonStyle.danger), bss.labelAsBadge)
    )
  }

  val staticMenuItems = List(
    MenuItem(1, _ => "Flights", Icon.plane, FlightsLoc),
    MenuItem(2, _ => "Staffing", Icon.dashboard, StaffingLoc)
  )

  def menuItems(airportConfigPotMP: ModelProxy[Pot[AirportConfig]]) = {
    val terminalDepsMenuItems = airportConfigPotMP().state match {
      case PotReady =>
        airportConfigPotMP().get.terminalNames.zipWithIndex.map {
          case (tn, idx) =>
            MenuItem(idx + staticMenuItems.length + 1, _ => tn, Icon.calculator, TerminalDepsLoc(tn))
        }.toList
      case _ =>
        List()
    }

    staticMenuItems ::: terminalDepsMenuItems
  }

  private class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      val airportConfigPotRCP = SPACircuit.connect(_.airportConfig)
      airportConfigPotRCP(airportConfigPotMP => {
        <.div(
          airportConfigPotMP().renderReady(airportConfig =>
            <.ul(bss.navbar, ^.className := "mr-auto")(
              //           build a list of menu items
              for (item <- menuItems(airportConfigPotMP)) yield
                <.li(^.key := item.idx, (props.currentLoc == item.location) ?= (^.className := "active"),
                  props.router.link(item.location)(item.icon, " ", item.label(props))))))
      })
    }
  }

  private val component = ReactComponentB[Props]("MainMenu")
    .renderBackend[Backend]
    //    .componentDidMount(scope => scope.backend.mounted(scope.props))
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Loc): ReactElement =
    component(Props(ctl, currentLoc))
}
