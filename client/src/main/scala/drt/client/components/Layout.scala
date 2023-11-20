package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain._
import drt.client.services.SPACircuit
import drt.client.services.handlers.{IsNewFeatureAvailable, TrackUser}
import io.kinoplan.scalajs.react.material.ui.core.MuiPaper
import io.kinoplan.scalajs.react.material.ui.core.MuiButton
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react._
import org.scalajs.dom
import org.scalajs.dom.console
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]) extends UseValueEq

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
                MuiPaper(sx = SxProps(Map("elevation" -> "4", "padding" -> "16px", "margin" -> "20px", "backgroundColor" -> "#0E2560")))(
                  MuiGrid(container = true)(
                    MuiGrid(item = true, xs = 4)(
                      MuiTypography(variant = "h4", sx = SxProps(Map("color" -> "white", "font-weight" -> "bold")))(
                        "Your feedback improves DRT for everyone"
                      )
                    ),
                    MuiGrid(item = true, xs = 2)(
                      MuiTypography(variant = "h5", sx = SxProps(Map("color" -> "white", "float" -> "left", "padding" -> "2px 0")))(
                        "Approx. 2 minutes to complete"
                      )
                    ),
                    MuiGrid(item = true, xs = 2)(
                      MuiButton(variant = "outlined", sx = SxProps(Map("border" -> "1px solid white", "color" -> "white", "font-weight" -> "bold")))(
                        "Give feedback >", ^.onClick --> Callback(dom.window.open("http://localhost:3000/feedback", "_blank")),
                      )
                    ),
                    MuiGrid(item = true, xs = 4)(
                      MuiIconButton(sx = SxProps(Map("color" -> "white", "font-weight" -> "bold", "float" -> "right")))
                      (^.onClick --> Callback(println("")), ^.aria.label := "Close", Icon.close)
                    )
                  )),
                <.div(^.className := "topbar",
                  <.div(^.className := "main-logo"),
                  <.div(^.className := "alerts", AlertsComponent())
                ),
                <.div(
                  <.div(
                    Navbar(Navbar.Props(props.ctl, props.currentLoc.page, user, airportConfig)),
                    <.div(^.className := "main-container",
                      <.div(^.className := "sub-nav-bar",
                        props.currentLoc.page match {
                          case TerminalPageTabLoc(terminalName, _, _, _) =>
                            val terminal = Terminal(terminalName)
                            <.div(^.className := "status-bar",
                              ApiStatusComponent(ApiStatusComponent.Props(
                                !airportConfig.noLivePortFeed,
                                airportConfig.useTimePredictions,
                                terminal)),
                              PassengerForecastAccuracyComponent(PassengerForecastAccuracyComponent.Props(terminal))
                            )
                          case _ => EmptyVdom
                        },
                        feedBackNavBar(user, airportConfig.portCode)
                      ),
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
    }).componentDidMount(_ =>
    Callback(SPACircuit.dispatch(IsNewFeatureAvailable())) >>
      Callback(SPACircuit.dispatch(TrackUser()))
  ).build

  def feedBackNavBar(user: LoggedInUser, port: PortCode): VdomTagOf[Div] =
    <.div(^.className := "feedback-widget",
      <.span(^.className := "feedback", "Is this page useful?"),
      <.span(PositiveFeedbackComponent(dom.window.location.toString, user.email.replace("\"", ""), port)),
      <.span(NegativeFeedbackComponent(dom.window.location.toString, user.email.replace("\"", ""), port)),
    )

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
