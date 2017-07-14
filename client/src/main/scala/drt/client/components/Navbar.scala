package drt.client.components

import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import drt.client.SPAMain.{Loc, TerminalDepsLoc}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.FlightsApi.TerminalName

import scala.collection.immutable.Seq

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc): VdomTagOf[html.Element] = {
    val airportConfigRCP = SPACircuit.connect(m => m.airportConfig)

    def currentTerminalOption(page: Loc): Option[TerminalName] = page match {
      case p: TerminalDepsLoc => Option(p.id)
      case _ => None
    }

    <.nav(^.className := "navbar navbar-inverse navbar-fixed-top",
      airportConfigRCP(airportConfigPotMP => {
        <.div(^.className := "container",
          airportConfigPotMP().renderReady(airportConfig => {
            <.div(
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode} Live"),
              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page),
                <.ul(^.className := "nav navbar-nav navbar-right",
                  <.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Staff movements", "Reason...", SDate.now(), SDate.now().addHours(1), "bottom")()),
                  <.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Breaks+15", "Breaks", SDate.now(), SDate.now().addMinutes(15), "bottom")()),
                  <.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Breaks+30", "Breaks", SDate.now(), SDate.now().addMinutes(30), "bottom")()),
                  <.li(StaffDeploymentsAdjustmentPopover(airportConfig.terminalNames, currentTerminalOption(page), "Breaks+45", "Breaks", SDate.now(), SDate.now().addMinutes(45), "bottom")())
                )))
          }))
      })
    )
  }
}
