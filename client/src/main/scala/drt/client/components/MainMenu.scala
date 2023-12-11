package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.SPAMain._
import drt.client.components.Icon._
import drt.client.services.JSDateConversions.SDate
import drt.client.spa.TerminalPageModes.Dashboard
import japgolly.scalajs.react._
import japgolly.scalajs.react.component.Js
import japgolly.scalajs.react.component.Scala.Unmounted
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.{Div, LI}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles._
import uk.gov.homeoffice.drt.feeds._
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode, PortRegion}

object MainMenu {
  case class Props(router: RouterCtl[Loc],
                   currentLoc: Loc,
                   feeds: Seq[FeedSourceStatuses],
                   airportConfig: AirportConfig,
                   user: LoggedInUser) extends UseValueEq

  case class MenuItem(idx: Int, label: String, icon: Icon, location: Loc, tabClasses: Iterable[String] = List(), linkClasses: Iterable[String] = List())

  private val dashboardMenuItem: MenuItem = MenuItem(0, "Dashboard", Icon.dashboard, UserDashboardLoc)

  private def statusMenuItem(position: Int, feeds: Seq[FeedSourceStatuses]): MenuItem =
    MenuItem(position, "Feeds", Icon.barChart, StatusLoc, tabClasses = List(feedsRag(feeds)))

  private val portConfigMenuItem: Int => MenuItem = (position: Int) => MenuItem(position, "Port Config", Icon.cogs, PortConfigLoc)

  private val forecastUploadFile: Int => MenuItem = (position: Int) => MenuItem(position, "Forecast Upload", Icon.upload, ForecastFileUploadLoc)

  private def feedsRag(feeds: Seq[FeedSourceStatuses]): String = {
    val statuses = feeds.map(f => FeedStatuses.ragStatus(SDate.now().millisSinceEpoch, f.feedSource.maybeLastUpdateThreshold, f.feedStatuses))
    val rag = if (statuses.contains(Red)) Red
    else if (statuses.contains(Amber)) Amber
    else Green

    rag.toString
  }

  def menuItems(airportConfig: AirportConfig, currentLoc: Loc, userRoles: Set[Role], feeds: Seq[FeedSourceStatuses]): List[MenuItem] = {
    val addFileUpload: List[(Role, Int => MenuItem)] =
      if (List(PortCode("LHR"), PortCode("STN"), PortCode("TEST")) contains airportConfig.portCode)
        List((PortFeedUpload, forecastUploadFile))
      else List.empty

    def terminalDepsMenuItem: List[(Role, Int => MenuItem)] = airportConfig.terminals.map { tn =>
      val terminalName = tn.toString
      val targetLoc = currentLoc match {
        case tptl: TerminalPageTabLoc if tptl.mode == Dashboard =>
          TerminalPageTabLoc(terminalName, tptl.mode, tptl.subMode, Map[String, String]())
        case tptl: TerminalPageTabLoc =>
          TerminalPageTabLoc(terminalName, tptl.mode, tptl.subMode,
            tptl.withUrlParameters(UrlDateParameter(tptl.maybeViewDate.map(_.toISOString)),
              UrlTimeRangeStart(tptl.timeRangeStartString),
              UrlTimeRangeEnd(tptl.timeRangeEndString)).queryParams)
        case _ => TerminalPageTabLoc(terminalName)
      }
      (BorderForceStaff, (offset: Int) =>
        MenuItem(offset, terminalName, Icon.calculator, targetLoc, linkClasses = Seq(s"terminal-${terminalName.toLowerCase}")))
    }.toList

    val restrictedMenuItems: List[(Role, Int => MenuItem)] = addFileUpload ++ terminalDepsMenuItem :+ ((ViewConfig, portConfigMenuItem))

    val nonTerminalUnrestrictedMenuItems = dashboardMenuItem :: Nil

    val itemsForLoggedInUser: List[MenuItem] = restrictedMenuItemsForRole(restrictedMenuItems, userRoles, nonTerminalUnrestrictedMenuItems.length)

    val nonTerminalMenuItems = nonTerminalUnrestrictedMenuItems ::: itemsForLoggedInUser
    nonTerminalMenuItems :+ statusMenuItem(nonTerminalMenuItems.length + airportConfig.terminals.size, feeds)
  }

  private def restrictedMenuItemsForRole(restrictedMenuItems: List[(Role, Int => MenuItem)], roles: Set[Role], startIndex: Int): List[MenuItem] = {
    val itemsForLoggedInUser = restrictedMenuItems.collect {
      case (role, menuItemCallback) if roles.contains(role) => menuItemCallback
    }.zipWithIndex.map {
      case (menuItemCallback, index) =>
        menuItemCallback(startIndex + index)
    }
    itemsForLoggedInUser
  }

  private class Backend() {
    def render(props: Props): VdomTagOf[Div] = {
      val children = for (item <- menuItems(props.airportConfig, props.currentLoc, props.user.roles, props.feeds)) yield {
        val active = (props.currentLoc, item.location) match {
          case (TerminalPageTabLoc(tn, _, _, _), TerminalPageTabLoc(tni, _, _, _)) => tn == tni
          case (current, itemLoc) => current == itemLoc
        }
        val classes = List(("active", active))
        <.div(^.key := item.idx, ^.classSet(classes: _*), ^.className := item.tabClasses.mkString(" "),
          props.router.link(item.location)(item.icon, " ", item.label, ^.className := item.linkClasses.mkString(" "))
        )
      }

      val navItems = if (props.user.portRoles.size > 1)
        children :+ <.div(PortSwitcher(props.user, props.airportConfig.portCode))
      else
        children
      <.div(
        ^.className := "main-menu-items",
        navItems.toTagMod
      )
    }
  }

  private val component = ScalaComponent.builder[Props]("MainMenu")
    .renderBackend[Backend]
    .build

  def apply(ctl: RouterCtl[Loc],
            currentLoc: Loc,
            feeds: Seq[FeedSourceStatuses],
            airportConfig: AirportConfig,
            user: LoggedInUser): VdomElement
  = component(Props(ctl, currentLoc, feeds, airportConfig, user))
}

object PortSwitcher {
  case class Props(user: LoggedInUser, portCode: PortCode) extends UseValueEq

  case class State(showDropDown: Boolean = false)

  val component: Js.ComponentSimple[Props, CtorType.Props, Unmounted[Props, State, Unit]] =
    ScalaComponent.builder[Props]("PortSwitcher")
      .initialState(State())
      .renderPS((scope, props, state) => {
        val showClass = if (state.showDropDown) "show" else ""
        val ports = props.user.portRoles.map(portRole => PortCode(portRole.name))
        if (ports.size == 2) {
          ports.find(_ != props.portCode).map { pc =>
            <.a(Icon.plane, " ", ^.href := SPAMain.urls.urlForPort(pc.toString), pc.iata)
          }.getOrElse(EmptyVdom)
        } else {
          val regions = PortRegion.regions.collect {
            case region if region.ports.intersect(ports).nonEmpty => region.name -> region.ports.intersect(ports)
          }.toList.sortBy(_._1)
          <.span(
            ^.className := "dropdown",
            <.a(Icon.plane, " ", "Switch port"),
            ^.onClick --> scope.modState(_.copy(showDropDown = !state.showDropDown)),
            if (state.showDropDown) <.div(^.className := "menu-overlay", ^.onClick --> scope.modState(_ => State())) else "",
            <.ul(^.className := s"main-menu__port-switcher dropdown-menu $showClass",
              if (regions.size == 1) listPorts(ports, props.portCode).toTagMod
              else listPortsByRegion(regions, props.portCode).toTagMod
            )
          )
        }
      }).build

  private def listPortsByRegion(regions: List[(String, Set[PortCode])], currentPort: PortCode): List[html_<^.VdomTagOf[LI]] =
    regions.flatMap { case (region, regionPorts) =>
      val regionLi = <.li(^.className := "dropdown-item", <.span(region, ^.disabled := true, ^.className := "non-selectable region"))
      regionLi :: listPorts(regionPorts, currentPort)
    }

  private def listPorts(ports: Set[PortCode], currentPort: PortCode): List[VdomTagOf[LI]] =
    ports.toList.sorted.map { p =>
      <.li(^.className := "dropdown-item",
        if (p == currentPort) <.span(p.iata, ^.disabled := true, ^.className := "non-selectable port")
        else <.a(^.href := SPAMain.urls.urlForPort(p.toString), p.iata)
      )
    }

  def apply(user: LoggedInUser, portCode: PortCode): VdomElement = component(Props(user, portCode))
}
