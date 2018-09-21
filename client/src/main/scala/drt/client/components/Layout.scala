package drt.client.components

import drt.client.SPAMain._
import drt.client.services.SPACircuit
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  @inline private def bss = GlobalStyles.bootstrapStyles

  val component = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      val loggedInUserPotRCP = SPACircuit.connect(m => (m.loggedInUserPot, m.userHasPortAccess))
      loggedInUserPotRCP(loggedInUserMP => {
        val (loggedInUser, userHasPortAccess) = loggedInUserMP()
        <.div(
          <.div(^.className := "main-logo"),
          <.div(^.className := "alerts", AlertsComponent())
          <.div(
            loggedInUser.renderReady(loggedInUser => {
              userHasPortAccess.renderReady(userHasPortAccess => {
                if (userHasPortAccess) {
                  val airportConfigRCP = SPACircuit.connect(m => m.airportConfig)

                  airportConfigRCP(airportConfigMP => {
                    val airportConfig = airportConfigMP()
                    <.div(
                      AlertsComponent(),
                      airportConfig.renderReady(airportConfig => {
                        <.div(
                          Navbar(props.ctl, props.currentLoc.page, loggedInUser, airportConfig),
                          <.div(^.className := "container",
                            <.div(<.div(props.currentLoc.render()))
                          ), VersionUpdateNotice())
                      }))
                  })
                } else <.div(RestrictedAccessByPortPage(loggedInUser))
              })
            })))
      })
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
