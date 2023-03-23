package drt.client.components

import diode.data.{Empty, Pot}
import drt.client.SPAMain.{ContactUsLoc, Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.SetSnackbarMessage
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import io.kinoplan.scalajs.react.material.ui.core.MuiSnackbar
import io.kinoplan.scalajs.react.material.ui.core.internal.Origin
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ReactEvent, ScalaComponent}
import org.scalajs.dom
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses
import uk.gov.homeoffice.drt.ports.AirportConfig

case class NavbarModel(feedStatuses: Pot[Seq[FeedSourceStatuses]], snackbarMessage: Pot[String])


object Navbar {
  case class Props(ctl: RouterCtl[Loc],
                   page: Loc,
                   loggedInUser: LoggedInUser,
                   airportConfig: AirportConfig)

  case class State(showDropDown: Boolean)

  def handleClose: (ReactEvent, String) => Callback = (_, _) => {
    Callback(SPACircuit.dispatch(SetSnackbarMessage(Empty)))
  }

  val component: Component[Props, State, Unit, CtorType.Props] = ScalaComponent.builder[Props]("NavBar")
    .initialState(if (dom.window.innerWidth > 768) State(true) else State(false))
    .renderPS { (scope, props, state) =>
      val rcp = SPACircuit.connect(m => NavbarModel(m.feedStatuses, m.snackbarMessage))

      def isLargeDisplay = dom.window.innerWidth > 800

      <.div(
        rcp { navbarModelProxy =>
          val navbarModel: NavbarModel = navbarModelProxy()

          val items = MainMenu.menuItems(props.airportConfig, props.page, props.loggedInUser.roles, navbarModel.feedStatuses.getOrElse(Seq()))
          val currentItem = items.find { i =>
            (i.location, props.page) match {
              case (TerminalPageTabLoc(tn, _, _, _), TerminalPageTabLoc(tni, _, _, _)) => tn == tni
              case (current, itemLoc) => current == itemLoc
            }
          }

          <.div(^.className := "main-menu-wrapper",
            navbarModel.snackbarMessage.renderReady { message =>
              MuiSnackbar(anchorOrigin = Origin(vertical = "top", horizontal = "right"),
                autoHideDuration = 5000,
                message = <.div(^.className := "muiSnackBar", message),
                open = true,
                onClose = handleClose)()
            },
            <.div(^.className := "main-menu-title",
              <.div(^.className := "main-menu-burger", Icon.bars),
              <.a(s"DRT ${props.airportConfig.portCode}"),
              currentItem.map(i => <.div(^.className := "main-menu-current-item", i.label)).getOrElse(""),
              ^.onClick --> {
                if (dom.window.innerWidth <= 768)
                  scope.modState(s => s.copy(showDropDown = !s.showDropDown))
                else Callback.empty
              }),
            if (isLargeDisplay || state.showDropDown)
              <.div(^.className := "main-menu-content",
                MainMenu(props.ctl, props.page, navbarModel.feedStatuses.getOrElse(Seq()), props.airportConfig, props.loggedInUser),
                <.div(^.className := "main-menu-items",
                  <.div(^.className := "contact-us-link", props.ctl.link(ContactUsLoc)(Icon.envelope, " ", "Contact Us")),
                  <.div(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                    ^.onClick --> Callback(GoogleEventTracker.sendEvent(props.airportConfig.portCode.toString, "Log Out", props.loggedInUser.id))))
                ))
            else EmptyVdom
          )
        }
      )
    }.build

  def apply(props: Props): Unmounted[Props, State, Unit] = component(props)
}
