package drt.client.components

import drt.client.SPAMain.Loc
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc): VdomTagOf[html.Element] = {
    val menuModelRCP = SPACircuit.connect(m => (m.airportConfig, m.feedStatuses, m.loggedInUserPot))

    <.nav(^.className := "navbar navbar-default",
      menuModelRCP(menuModelPotMP => {
        val (airportConfigPot, feedStatusesPot, loggedInUserPot) = menuModelPotMP()
        <.div(^.className := "container",
          airportConfigPot.render(airportConfig => {
            val contactLink = airportConfig.contactEmail.map(contactEmail => {
              <.a(Icon.envelope, "Email Us", ^.href := "mailto:" + contactEmail + "?subject=Email from DRT v2 Page&body=Please give as much detail as possible about your enquiry here")
            }).getOrElse(TagMod(""))

            <.div(^.className := "navbar-drt",
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode}"),
              loggedInUserPot.renderReady(loggedInUser =>

              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page, feedStatusesPot.getOrElse(Seq()), airportConfig, loggedInUser.roles),
                <.ul(^.className := "nav navbar-nav navbar-right",
                  <.li(contactLink),
                  <.li(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value,
                    ^.onClick --> Callback(GoogleEventTracker.sendEvent(airportConfig.portCode, "Log Out", loggedInUser.id))))
                )
              ))
            )
          }),
          loggedInUserPot.render(loggedInUser => <.input.hidden(^.id:="user-id", ^.value:=loggedInUser.id)),
          loggedInUserPot.renderEmpty(<.input.hidden(^.id:="user-id"))
          )
      })
    )
  }
}
