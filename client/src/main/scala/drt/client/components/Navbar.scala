package drt.client.components

import drt.client.SPAMain.Loc
import drt.client.services.SPACircuit
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc): VdomTagOf[html.Element] = {
    val airportConfigRCP = SPACircuit.connect(m => (m.airportConfig, m.feedStatuses))

    <.nav(^.className := "navbar navbar-default",
      airportConfigRCP(airportConfigPotMP => {
        val (airportConfigPot, feedStatusesPot) = airportConfigPotMP()
        <.div(^.className := "container",
          airportConfigPot.render(airportConfig => {
            val contactLink = airportConfig.contactEmail.map(contactEmail => {
              <.a(Icon.envelope, "Email Us", ^.href := "mailto:" + contactEmail + "?subject=Email from DRT v2 Page&body=Please give as much detail as possible about your enquiry here")
            }).getOrElse(TagMod(""))

            <.div(^.className := "navbar-drt",
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode}"),
              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page, feedStatusesPot.getOrElse(Seq())),
                <.ul(^.className := "nav navbar-nav navbar-right",
                  <.li(contactLink),
                  <.li(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value))
                )
              ))
          }))
      })
    )
  }
}
