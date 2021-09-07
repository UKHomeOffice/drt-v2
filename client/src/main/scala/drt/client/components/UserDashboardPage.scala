package drt.client.components

import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import uk.gov.homeoffice.drt.auth.Roles.{BorderForceStaff, CedatStaff, PortOperatorStaff}

object UserDashboardPage {

  case class Props(router: RouterCtl[Loc])

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("UserDashboard")
    .render_P(p => {
      val loggedInUserRCP = SPACircuit.connect(_.loggedInUserPot)
      loggedInUserRCP(loggedInUserMP => {
        <.div(loggedInUserMP().renderReady(user => {
          if (user.hasRole(PortOperatorStaff) || user.hasRole(CedatStaff))
            PortExportDashboardPage(user)
          else if (user.hasRole(BorderForceStaff))
            PortDashboardPage(p.router, PortDashboardLoc(None))
          else
            <.div("You have successfully logged into DRT but your account has not been configured correctly. Please contact us for assistance.")
        }))
      })
    })
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"dashboard")
    })
    .build

  def apply(router: RouterCtl[Loc]): VdomElement = component(Props(router))
}
