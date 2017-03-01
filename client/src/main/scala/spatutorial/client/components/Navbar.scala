package spatutorial.client.components

import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html
import spatutorial.client.SPAMain.Loc
import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.client.services.SPACircuit

object Navbar {
  def apply(ctl: RouterCtl[Loc], page: Loc): ReactTagOf[html.Element] = {
    val airportConfigRCP = SPACircuit.connect(m => m.airportConfig)

    <.nav(^.className := "navbar navbar-inverse navbar-fixed-top",
      airportConfigRCP(airportConfigPotMP => {
        <.div(^.className := "container",
          airportConfigPotMP().renderReady(airportConfig => {
            <.div(
              <.span(^.className := "navbar-brand", s"DRT ${airportConfig.portCode} Live"),
              <.div(^.className := "collapse navbar-collapse", MainMenu(ctl, page),
                <.ul(^.className := "nav navbar-nav navbar-right",
                  <.li(StaffMovementsPopover(airportConfig.terminalNames, page, "IS81", "IS81", SDate.now(), SDate.now().addHours(1), "bottom")()))))

          }))
      })
    )
  }
}
