package drt.client.components

import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import drt.client.SPAMain.Loc
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc): VdomTagOf[html.Element] = {
    val airportConfigRCP = SPACircuit.connect(m => m.airportConfig)

    <.nav(^.className := "navbar navbar-inverse navbar-fixed-top",
      airportConfigRCP(airportConfigPotMP => {
        <.div(^.className := "container",
          airportConfigPotMP().renderReady(airportConfig => {
            <.div(
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode} Live"),
              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page),
                <.ul(^.className := "nav navbar-nav navbar-right",
                  <.li(StaffMovementsPopover(airportConfig.terminalNames, page, "Staff movements", "Reason...", SDate.now(), SDate.now().addHours(1), "bottom")()),
                  <.li(StaffMovementsPopover(airportConfig.terminalNames, page, "Breaks+15", "Breaks", SDate.now(), SDate.now().addMinutes(15), "bottom")()),
                  <.li(StaffMovementsPopover(airportConfig.terminalNames, page, "Breaks+30", "Breaks", SDate.now(), SDate.now().addMinutes(30), "bottom")()),
                  <.li(StaffMovementsPopover(airportConfig.terminalNames, page, "Breaks+45", "Breaks", SDate.now(), SDate.now().addMinutes(45), "bottom")())
                )))

          }))
      })
    )
  }
}
