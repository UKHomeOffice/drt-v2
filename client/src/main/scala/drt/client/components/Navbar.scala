package drt.client.components

import diode.data.{Empty, Pot}
import drt.client.SPAMain.{ContactUsLoc, Loc}
import drt.client.actions.Actions.SetSnackbarMessage
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.FeedSourceStatuses
import io.kinoplan.scalajs.react.material.ui.core.MuiSnackbar
import io.kinoplan.scalajs.react.material.ui.core.internal.Origin
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEvent, ScalaComponent}
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.AirportConfig
import org.scalajs.dom

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


  val component = ScalaComponent.builder[Props]("NavBar")
    .initialState(if (dom.window.innerWidth > 800) State(true) else State(false))
    .renderPS((scope, props, state) => {
      val rcp = SPACircuit.connect(m => NavbarModel(m.feedStatuses, m.snackbarMessage))
      <.nav(^.className := "navbar navbar-default",
        rcp(navbarModelProxy => {
          val navbarModel: NavbarModel = navbarModelProxy()
          <.div(^.className := "navbar-drt",
            navbarModel.snackbarMessage.renderReady { message =>
              MuiSnackbar(anchorOrigin = Origin(vertical = "top", horizontal = "right"),
                autoHideDuration = 5000,
                message = <.div(^.className := "muiSnackBar", message),
                open = true,
                onClose = handleClose)()
            },
            <.span(^.className := "navbar-brand",
              <.a(Icon.bars, " ", s"DRT ${props.airportConfig.portCode}"),
              ^.onClick --> scope.modState(_.copy(showDropDown = !state.showDropDown))),
            <.div(^.className := "navbar-collapse",
              if (state.showDropDown)
                MainMenu(props.ctl, props.page, navbarModel.feedStatuses.getOrElse(Seq()), props.airportConfig, props.loggedInUser) else "",
              <.ul(^.className := "nav navbar-nav navbar-right",
                <.li(^.className := "contact-us-link", props.ctl.link(ContactUsLoc)(Icon.envelope, " ", "Contact Us")),
                <.li(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                  ^.onClick --> Callback(GoogleEventTracker.sendEvent(props.airportConfig.portCode.toString, "Log Out", props.loggedInUser.id))))
              ))
          )
        }
        ))
    }).build

  def apply(props: Props) = component(props)

}
