package drt.client.components

import drt.client.SPAMain._
import drt.client.components.Icon._
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared._
import drt.shared.CrunchApi.MillisSinceEpoch
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.LI

import scala.collection.immutable

object MainMenu {
  @inline private def bss: BootstrapStyles.type = GlobalStyles.bootstrapStyles

  case class Props(router: RouterCtl[Loc], currentLoc: Loc, feeds: Seq[FeedStatuses])

  case class MenuItem(idx: Int, label: Props => VdomNode, icon: Icon, location: Loc, classes: List[String] = List())

  val dashboardMenuItem = MenuItem(0, _ => "Dashboard", Icon.dashboard, TerminalsDashboardLoc(None))

  def statusMenuItem(position: Int, feeds: Seq[FeedStatuses]): MenuItem = MenuItem(position, _ => s"Feeds", Icon.barChart, StatusLoc, List(feedsRag(feeds)))

  def feedsRag(feeds: Seq[FeedStatuses]): String = {
    val rag = if (feeds.map(_.ragStatus(SDate.now().millisSinceEpoch)).contains(Red)) Red
    else if (feeds.map(_.ragStatus(SDate.now().millisSinceEpoch)).contains(Amber)) Amber
    else Green

    rag.toString
  }

  def menuItems(airportConfig: AirportConfig, currentLoc: Loc, userRoles: List[String], feeds: Seq[FeedStatuses]): List[MenuItem] = {
    def terminalDepsMenuItems(idxOffset: Int): List[MenuItem] = airportConfig.terminalNames.zipWithIndex.map {
      case (tn, idx) =>
        val targetLoc = currentLoc match {
          case tptl: TerminalPageTabLoc =>
            TerminalPageTabLoc(tn, tptl.mode, tptl.subMode, tptl.date, tptl.timeRangeStartString, tptl.timeRangeEndString)
          case _ => TerminalPageTabLoc(tn)
        }
        MenuItem(idx + idxOffset, _ => tn, Icon.calculator, targetLoc)
    }.toList

    val nonTerminalMenuItems = dashboardMenuItem :: Nil

    (nonTerminalMenuItems ::: terminalDepsMenuItems(nonTerminalMenuItems.length)) :+ statusMenuItem(nonTerminalMenuItems.length + airportConfig.terminalNames.length, feeds)
  }

  def lastUpdatedDescription(maybeLastUpdated: Option[MillisSinceEpoch]): String = maybeLastUpdated.map(lastUpdated => {
    val secondsAgo = (SDate.now().millisSinceEpoch - lastUpdated) / 1000
    val minutesAgo = secondsAgo / 60
    if (minutesAgo > 1) s"$minutesAgo mins" else if (minutesAgo == 1) s"$minutesAgo min" else "< 1 min"
  }).getOrElse("n/a")

  private class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      val airportConfigAndRoles = SPACircuit.connect(m => (m.airportConfig, m.userRoles))

      airportConfigAndRoles(airportConfigAndRolesPotMP => {
        val (airportConfigPot, userRolesPot) = airportConfigAndRolesPotMP()
        <.div(
          airportConfigPot.render(airportConfig => {
            val children: immutable.Seq[TagOf[LI]] = for (item <- menuItems(airportConfig, props.currentLoc, userRolesPot.getOrElse(List()), props.feeds)) yield {
              val active = (props.currentLoc, item.location) match {
                case (TerminalPageTabLoc(tn, _, _, _, _, _), TerminalPageTabLoc(tni, _, _, _, _, _)) => tn == tni
                case (current, itemLoc) => current == itemLoc
              }
              val classes = List(("active", active))
              <.li(^.key := item.idx, ^.classSet(classes: _*), ^.className := item.classes.mkString(" "),
                props.router.link(item.location)(item.icon, " ", item.label(props))
              )
            }
            <.ul(^.classSet(bss.navbarClsSet.map(cn => (cn, true)): _*), ^.className := "mr-auto")(children.toTagMod)
          }))
      })
    }
  }

  private val component = ScalaComponent.builder[Props]("MainMenu")
    .renderBackend[Backend]
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Loc, feeds: Seq[FeedStatuses]): VdomElement = component(Props(ctl, currentLoc, feeds))
}
