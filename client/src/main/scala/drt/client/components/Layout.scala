package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain._
import drt.client.services.SPACircuit
import drt.shared.AirportConfig
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, _}
import org.scalajs.dom
import org.scalajs.dom.console
import org.scalajs.dom.html.UList
import uk.gov.homeoffice.drt.auth.LoggedInUser

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc])

  case class LayoutModelItems(user: Pot[LoggedInUser],
                              airportConfig: Pot[AirportConfig]
                             ) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      val layoutModelItemsRCP = SPACircuit.connect { m =>
        LayoutModelItems(m.loggedInUserPot, m.airportConfig)
      }
      layoutModelItemsRCP { modelProxy =>
        console.log("rendering layout")
        <.div({
          val model = modelProxy()

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
                    Navbar(props.ctl, props.currentLoc.page, user, airportConfig),
                    <.div(buildFeedBackNavBar(user)),
                    <.div(^.className := "container",
                      <.div(<.div(props.currentLoc.render()))
                    ),
                    VersionUpdateNotice()
                  )
                )
              )
            }
          }
        })
      }
    })
    .build

  def buildFeedBackNavBar(user: LoggedInUser): VdomTagOf[UList] = {
    <.ul(^.className := "nav navbar-nav navbar-right",
      <.li(<.div(<.span(^.className := "btn", "is this page useful ?", ^.disabled := true))),
      <.li(PositiveFeedbackComponent(dom.window.location.toString, user)),
      <.li(NegativeFeedbackComponent(dom.window.location.toString, user))
    )
  }

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
