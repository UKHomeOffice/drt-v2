package drt.client.components

import drt.client.SPAMain.{ContactUsLoc, Loc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.{AirportConfig, LoggedInUser}
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc, loggedInUser: LoggedInUser, airportConfig: AirportConfig): VdomTagOf[html.Element] = {
    val feedStatusesRCP = SPACircuit.connect(_.feedStatuses)

    <.nav(^.className := "navbar navbar-default",
      feedStatusesRCP(feedStatusesPotMP => {
        val feedStatusesPot = feedStatusesPotMP()
        <.div(^.className := "navbar-drt",
          <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode}"),


          <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page, feedStatusesPot.getOrElse(Seq()), airportConfig, loggedInUser.roles),
            <.ul(^.className := "nav navbar-nav navbar-right",
              <.li(^.className := "contact-us-link",ctl.link(ContactUsLoc)(Icon.envelope, " ", "Contact Us")),
              <.li(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                ^.onClick --> Callback(GoogleEventTracker.sendEvent(airportConfig.portCode.toString, "Log Out", loggedInUser.id))))
            ))
        )}
      ))
  }
}
