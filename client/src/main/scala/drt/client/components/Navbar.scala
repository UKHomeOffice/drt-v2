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
import japgolly.scalajs.react.{Callback, ReactEvent}
import org.scalajs.dom.html
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.AirportConfig

case class NavbarModel(feedStatuses: Pot[Seq[FeedSourceStatuses]], snackbarMessage: Pot[String])

object Navbar {
  def apply(ctl: RouterCtl[Loc],
            page: Loc,
            loggedInUser: LoggedInUser,
            airportConfig: AirportConfig): VdomTagOf[html.Element] = {
    val rcp = SPACircuit.connect(m => NavbarModel(m.feedStatuses, m.snackbarMessage))

    def handleClose: (ReactEvent, String) => Callback = (_, _) => {
      Callback(SPACircuit.dispatch(SetSnackbarMessage(Empty)))
    }

    <.nav(^.className := "navbar navbar-default",
      rcp(navbarModelProxy => {
        val navbarModel = navbarModelProxy()
        <.div(^.className := "navbar-drt",
          navbarModel.snackbarMessage.renderReady { message =>
            MuiSnackbar(anchorOrigin = Origin(vertical = "top", horizontal = "right"),
              autoHideDuration = 5000,
              message = <.div(^.className := "muiSnackBar", message),
              open = true,
              onClose = handleClose)()
          },
          <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode}"),
          <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page, navbarModel.feedStatuses.getOrElse(Seq()), airportConfig, loggedInUser),
            <.ul(^.className := "nav navbar-nav navbar-right",
              <.li(^.className := "contact-us-link", ctl.link(ContactUsLoc)(Icon.envelope, " ", "Contact Us")),
              <.li(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                ^.onClick --> Callback(GoogleEventTracker.sendEvent(airportConfig.portCode.toString, "Log Out", loggedInUser.id))))
            ))
        )
      }
      ))
  }
}
