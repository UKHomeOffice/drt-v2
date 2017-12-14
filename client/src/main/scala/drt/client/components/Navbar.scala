package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.FlightsApi.TerminalName
import japgolly.scalajs.react.extra.router.RouterCtl
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
            <.div(^.className := "navbar-drt",
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode}"),
              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page),
                <.ul(^.className := "nav navbar-nav navbar-right")
                  //<.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Staff movements", "Reason...", SDate.now(), SDate.now().addHours(1), "bottom")()),
                  //<.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Breaks+15", "Breaks", SDate.now(), SDate.now().addMinutes(15), "bottom")()),
                  //<.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Breaks+30", "Breaks", SDate.now(), SDate.now().addMinutes(30), "bottom")()),
                  //<.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Breaks+45", "Breaks", SDate.now(), SDate.now().addMinutes(45), "bottom")())
                //)))
              ))
          }))
      })
    )
  }
}
