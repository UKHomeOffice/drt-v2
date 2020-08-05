package drt.client.components

import diode.data.Pot
import drt.client.SPAMain.{Loc, PortDashboardLoc}
import drt.shared._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.raw.Element

import scala.scalajs.js

object PortDashboardPage {

  case class Props(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc)

  case class DisplayPeriod(start: SDateLike, end: SDateLike)

  object DisplayPeriod {
    def apply(start: SDateLike, hours: Int = 3): DisplayPeriod = DisplayPeriod(start, start.addHours(hours))
  }

  case class PortDashboardModel(
                                 airportConfig: Pot[AirportConfig],
                                 portState: Pot[PortState],
                                 featureFlags: Pot[Map[String, Boolean]]
                               )

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("PortDashboard")
    .render_P(p => {


      <.div(
//        TippyJSComponent("here's the popup")(<.div("Here's the trigger"))
      )
    })
    .build


  def apply(router: RouterCtl[Loc], dashboardPage: PortDashboardLoc = PortDashboardLoc(None)): VdomElement = component(Props(router, dashboardPage))
}
