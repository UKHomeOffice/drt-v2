package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.FlightsApi.TerminalName
import japgolly.scalajs.react.extra.router.{BaseUrl, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc): VdomTagOf[html.Element] = {
    val airportConfigRCP = SPACircuit.connect(m => m.airportConfig)

    def currentTerminalOption(page: Loc): Option[TerminalName] = page match {
      case p: TerminalPageTabLoc => Option(p.terminal)
      case _ => None
    }

    <.nav(^.className := "navbar navbar-default",
      airportConfigRCP(airportConfigPotMP => {
        <.div(^.className := "container",
          airportConfigPotMP().render(airportConfig => {
            val contactLink = airportConfig.contactEmail.map(contactEmail => {
              <.li(<.a(Icon.envelope, "Email Us", ^.href := "mailto:" + contactEmail))
            }).getOrElse(TagMod(""))

            <.div(^.className := "navbar-drt",
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode}"),
              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page),
                <.ul(^.className := "nav navbar-nav navbar-right",
                  contactLink,
                  <.li(<.a(Icon.signOut, "Log Out", ^.href := "/oauth/logout?redirect=" + BaseUrl.until_#.value))
                )
              ))
          }))
      })
    )
  }
}
