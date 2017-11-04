package drt.client.components

import diode.data.Pot
import diode.react.ReactConnectProxy
import drt.client.SPAMain._
import drt.client.components.Icon._
import drt.client.services.SPACircuit
import drt.shared.AirportConfig
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.LI

import scala.collection.immutable

object MainMenu {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class Props(router: RouterCtl[Loc], currentLoc: Loc)

  case class MenuItem(idx: Int, label: (Props) => VdomNode, icon: Icon, location: Loc)

  val staticMenuItems = List(
    MenuItem(0, _ => "Dashboard", Icon.dashboard, TerminalsDashboardLoc(None))
  )

  def menuItems(airportConfig: AirportConfig): List[MenuItem] = {
    val terminalDepsMenuItems = airportConfig.terminalNames.zipWithIndex.map {
      case (tn, idx) =>
        MenuItem(idx + staticMenuItems.length, _ => tn, Icon.calculator, TerminalPageTabLoc(tn))
    }.toList

    staticMenuItems ::: terminalDepsMenuItems
  }

  private class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      val airportConfigPotRCP: ReactConnectProxy[Pot[AirportConfig]] = SPACircuit.connect(_.airportConfig)

      airportConfigPotRCP(airportConfigPotMP => {
        <.div(
          airportConfigPotMP().renderReady(airportConfig => {

            val children: immutable.Seq[TagOf[LI]] = for (item <- menuItems(airportConfig)) yield {
              val active = (props.currentLoc, item.location) match {
                case (TerminalPageTabLoc(tn, _, _, _), TerminalPageTabLoc(tni, _, _, _)) => tn == tni
                case (current, itemLoc) => current == itemLoc
              }
              val classes = Seq(("active", active))
              <.li(^.key := item.idx, ^.classSet(classes: _*),
                props.router.link(item.location)(item.icon, " ", item.label(props)))
            }
            <.ul(^.classSet(bss.navbarClsSet.map(cn => (cn, true)): _*), ^.className := "mr-auto")(children.toTagMod)}))
      })
    }
  }

  private val component = ScalaComponent.builder[Props]("MainMenu")
    .renderBackend[Backend]
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Loc): VdomElement =
    component(Props(ctl, currentLoc))
}
