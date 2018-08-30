package drt.client.components

import diode.data.Pot
import diode.react.ModelProxy
import drt.client.SPAMain._
import drt.client.services.SPACircuit
import drt.shared.{AirportConfig, Alert}
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  @inline private def bss = GlobalStyles.bootstrapStyles

  val component = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      val modelRCP = SPACircuit.connect(m => (m.airportConfig, m.loggedInUserPot))
      <.div(
        <.div(^.className := "main-logo"),
        AlertsComponent(),
        <.div(
          // here we use plain Bootstrap class names as these are specific to the top level layout defined here
          Navbar(props.ctl, props.currentLoc.page),
          <.div(^.className := "container",
            modelRCP(modelPotMP => {
              val (airportConfigPot, loggedInUserPot) = modelPotMP()
              <.div(
                loggedInUserPot.render(_ =>
                  <.div(
                  airportConfigPot.render(_ => props.currentLoc.render()),
                  airportConfigPot.renderEmpty( if (!airportConfigPot.isPending) {RestrictedAccessByPortPage()} else "" )
                  )
                )
              )
            }
            )
          ),
          VersionUpdateNotice()
        )
      )
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
