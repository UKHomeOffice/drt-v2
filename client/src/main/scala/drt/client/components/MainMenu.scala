package drt.client.components

import drt.client.SPAMain._
import drt.client.components.Icon._
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.LI

import scala.collection.immutable

object MainMenu {
  @inline private def bss: BootstrapStyles.type = GlobalStyles.bootstrapStyles

  case class Props(router: RouterCtl[Loc], currentLoc: Loc, feeds: Seq[FeedStatuses], airportConfig: AirportConfig, roles: Set[Role])

  case class MenuItem(idx: Int, label: Props => VdomNode, icon: Icon, location: Loc, classes: List[String] = List())

  val dashboardMenuItem: MenuItem = MenuItem(0, _ => "Dashboard", Icon.dashboard, UserDashboardLoc)

  def usersMenuItem(position: Int): MenuItem = MenuItem(position, _ => "Users", Icon.users, KeyCloakUsersLoc)

  def alertsMenuItem(position: Int): MenuItem = MenuItem(position, _ => "Alerts", Icon.briefcase, AlertLoc, List("alerts-link"))

  def statusMenuItem(position: Int, feeds: Seq[FeedStatuses]): MenuItem = MenuItem(position, _ => s"Feeds", Icon.barChart, StatusLoc, List(feedsRag(feeds)))

  def portConfigMenuItem(position: Int): MenuItem = MenuItem(position, _ => s"Port Config", Icon.cogs, PortConfigLoc)

  def feedsRag(feeds: Seq[FeedStatuses]): String = {
    val rag = if (feeds.map(_.ragStatus(SDate.now().millisSinceEpoch)).contains(Red)) Red
    else if (feeds.map(_.ragStatus(SDate.now().millisSinceEpoch)).contains(Amber)) Amber
    else Green

    rag.toString
  }


  def menuItems(airportConfig: AirportConfig, currentLoc: Loc, userRoles: Set[Role], feeds: Seq[FeedStatuses]): List[MenuItem] = {
    def terminalDepsMenuItem: List[(Role, Int => MenuItem)] = airportConfig.terminalNames.map { tn =>
        val targetLoc = currentLoc match {
          case tptl: TerminalPageTabLoc =>
            TerminalPageTabLoc(tn, tptl.mode, tptl.subMode,
              tptl.withUrlParameters(UrlDateParameter(tptl.date),
                UrlTimeRangeStart(tptl.timeRangeStartString),
                UrlTimeRangeEnd(tptl.timeRangeEndString)).queryParams)
          case _ => TerminalPageTabLoc(tn)
        }
        (BorderForceStaff, (offset: Int) => MenuItem(offset, _ => tn, Icon.calculator, targetLoc))
    }.toList

    val restrictedMenuItems: List[(Role, Int => MenuItem)] = List(
      (ManageUsers, usersMenuItem _),
      (CreateAlerts, alertsMenuItem _)
    ) ++ terminalDepsMenuItem :+ (ViewConfig, portConfigMenuItem _)

    val nonTerminalUnrestrictedMenuItems = dashboardMenuItem :: Nil

    val itemsForLoggedInUser: List[MenuItem] = restrictedMenuItemsForRole(restrictedMenuItems, userRoles, nonTerminalUnrestrictedMenuItems.length)

    val nonTerminalMenuItems = nonTerminalUnrestrictedMenuItems ::: itemsForLoggedInUser
    nonTerminalMenuItems  :+ statusMenuItem(nonTerminalMenuItems.length + airportConfig.terminalNames.length, feeds)
  }

  def restrictedMenuItemsForRole(restrictedMenuItems: List[(Role, Int => MenuItem)], roles: Set[Role], startIndex: Int): List[MenuItem] = {
    val itemsForLoggedInUser = restrictedMenuItems.collect {
      case (role, menuItemCallback) if roles.contains(role) => menuItemCallback
    }.zipWithIndex.map {
      case (menuItemCallback, index) =>
        menuItemCallback(startIndex + index)
    }
    itemsForLoggedInUser
  }

  def lastUpdatedDescription(maybeLastUpdated: Option[MillisSinceEpoch]): String = maybeLastUpdated.map(lastUpdated => {
    val secondsAgo = (SDate.now().millisSinceEpoch - lastUpdated) / 1000
    val minutesAgo = secondsAgo / 60
    if (minutesAgo > 1) s"$minutesAgo mins" else if (minutesAgo == 1) s"$minutesAgo min" else "< 1 min"
  }).getOrElse("n/a")

  private class Backend($: BackendScope[Props, Unit]) {
    def render(props: Props) = {
      val children: immutable.Seq[TagOf[LI]] = for (item <- menuItems(props.airportConfig, props.currentLoc, props.roles, props.feeds)) yield {
        val active = (props.currentLoc, item.location) match {
          case (TerminalPageTabLoc(tn, _, _, _), TerminalPageTabLoc(tni, _, _, _)) => tn == tni
          case (current, itemLoc) => current == itemLoc
        }
        val classes = List(("active", active))
        <.li(^.key := item.idx, ^.classSet(classes: _*), ^.className := item.classes.mkString(" "),
          props.router.link(item.location)(item.icon, " ", item.label(props))
        )
      }
      <.div(
        <.ul(^.classSet(bss.navbarClsSet.map(cn => (cn, true)): _*), ^.className := "mr-auto")(children.toTagMod)
      )
    }
  }

  private val component = ScalaComponent.builder[Props]("MainMenu")
    .renderBackend[Backend]
    .componentDidMount(p => Callback.log("mainmenu did mount"))
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Loc, feeds: Seq[FeedStatuses], airportConfig: AirportConfig, roles: Set[Role]): VdomElement
  = component(Props(ctl, currentLoc, feeds, airportConfig, roles))
}
