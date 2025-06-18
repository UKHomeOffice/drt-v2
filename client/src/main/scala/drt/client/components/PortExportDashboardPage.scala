package drt.client.components

import diode.UseValueEq
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{SPACircuit, ViewLive}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.auth.LoggedInUser

object PortExportDashboardPage {

  case class Props(loggedInUser: LoggedInUser) extends UseValueEq

  val component = ScalaComponent.builder[Props]("PortExportDashboard")
    .render_P(p => {
      val airportConfigRCP = SPACircuit.connect(_.airportConfig)

      airportConfigRCP(airportConfigMP => {
        <.div(^.className := "terminal-export-dashboard", airportConfigMP().renderReady(config => {
          val terminals = config.terminals(SDate.now().toLocalDate)
          <.div(terminals.map(tn => {
            <.div(
              <.h3(s"Terminal $tn"),
              MultiDayExportComponent(config.portCode, tn, terminals, ViewLive, SDate.now(), p.loggedInUser)
            )
          }).toTagMod)
        }))
      })
    })
    .build

  def apply(loggedInUser: LoggedInUser): VdomElement = component(Props(loggedInUser))
}
