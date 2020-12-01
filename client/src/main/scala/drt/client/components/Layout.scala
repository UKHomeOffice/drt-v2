package drt.client.components

import diode.UseValueEq
import diode.data.{Pending, Pot, Ready}
import drt.client.SPAMain._
import drt.client.services.SPACircuit
import drt.shared.{AirportConfig, ApplicationConfig}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom.console
import uk.gov.homeoffice.drt.auth.LoggedInUser

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  case class LayoutModelItems(user: Pot[LoggedInUser],
                              applicationConfig: Pot[ApplicationConfig],
                              airportConfig: Pot[AirportConfig]
                             ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      val layoutModelItemsRCP = SPACircuit.connect { m =>
        LayoutModelItems(m.loggedInUserPot, m.applicationConfig, m.airportConfig)
      }
      layoutModelItemsRCP { modelProxy =>
        console.log("rendering layout")
        <.div({
          val model = modelProxy()

          model.applicationConfig.renderReady { appConfig =>
            model.airportConfig.renderReady { airportConfig =>
              model.user.renderReady { user =>
                <.div(
                  <.div(^.className := "topbar",
                    <.div(^.className := "main-logo"),
                    EnvironmentWarningComponent(),
                    <.div(^.className := "alerts", AlertsComponent())
                  ),
                  <.div(
                    <.div(
                      Navbar(props.ctl, props.currentLoc.page, user, airportConfig, appConfig),
                      <.div(^.className := "container",
                        <.div(<.div(props.currentLoc.render()))
                      ),
                      VersionUpdateNotice()
                    )
                  )
                )
              }
            }
          }
        })
      }
    })
    .build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
