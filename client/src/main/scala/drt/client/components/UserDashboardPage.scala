package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, PortOperatorStaff}

object UserDashboardPage {

  case class Props(router: RouterCtl[Loc]) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("UserDashboard")
    .render_P(p => {
      val loggedInUserRCP = SPACircuit.connect(m => (m.loggedInUserPot, m.airportConfig))
      loggedInUserRCP { loggedInUserMP =>
        val (loggedInUser, airportConfig) = loggedInUserMP()

        <.div(loggedInUser.renderReady(user => {
          if (user.hasRole(PortOperatorStaff))
            PortExportDashboardPage(user)
          else if (user.hasRole(BorderForceStaff))
            PortDashboardPage(p.router, PortDashboardLoc(None), airportConfig)
          else
            <.div("You have successfully logged into DRT but your account has not been configured correctly. Please contact us for assistance.")
        }))

      }
    })
    .build

  def apply(router: RouterCtl[Loc]): VdomElement = component(Props(router))
}
