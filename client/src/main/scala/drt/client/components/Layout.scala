package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain._
import drt.client.services.SPACircuit
import drt.shared.LoggedInUser
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  case class LoggedInUserAndHasPortAccess(userPot: Pot[LoggedInUser], hasPortAccessPot: Pot[Boolean]) extends UseValueEq

  @inline private def bss = GlobalStyles.bootstrapStyles

  val component = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      val loggedInUserAndDisplayDialogPotRCP = SPACircuit.connect(m => (LoggedInUserAndHasPortAccess(m.loggedInUserPot, m.userHasPortAccess), m.displayAlertDialog))
      loggedInUserAndDisplayDialogPotRCP(loggedInUserMPAndDialog => {
        val (userAndHasPortAccess: LoggedInUserAndHasPortAccess, displayDialogPot: Pot[Boolean]) = loggedInUserMPAndDialog()
        <.div(
          <.div(^.className := "topbar",
            <.div(^.className := "main-logo"),
            <.div(^.className := "alerts", AlertsComponent())
          ),
          <.div(
            userAndHasPortAccess.userPot.renderReady(loggedInUser => {
              userAndHasPortAccess.hasPortAccessPot.renderReady(userHasPortAccess => {
                if (userHasPortAccess) {
                  val airportConfigRCP = SPACircuit.connect(_.airportConfig)

                  airportConfigRCP(airportConfigMP => {
                    val airportConfig = airportConfigMP()
                    <.div(
                      airportConfig.renderReady(airportConfig => {
                        <.div(
                          Navbar(props.ctl, props.currentLoc.page, loggedInUser, airportConfig),
                          <.div(^.className := "container",
                            <.div(<.div(props.currentLoc.render()))
                          ), VersionUpdateNotice())
                      }), displayDialogPot.renderReady(displayDialog => PortRestrictionsModalAlert(displayDialog, loggedInUser)))
                  })

                } else <.div(RestrictedAccessByPortPage(loggedInUser))
              })
            }))
        )
      })
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
