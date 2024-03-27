package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.SPAMain
import drt.client.SPAMain._
import drt.client.components.styles.DrtTheme.buttonTheme
import drt.client.services.SPACircuit
import drt.client.services.handlers._
import drt.shared.{Alert, ContactDetails}
import io.kinoplan.scalajs.react.material.ui.core._
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import japgolly.scalajs.react._
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.{Resolution, RouterCtl}
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom
import org.scalajs.dom.console
import uk.gov.homeoffice.drt.ABFeature
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import scala.scalajs.js

object Layout {

  case class Props(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]) extends UseValueEq

  case class LayoutModelItems(user: Pot[LoggedInUser],
                              airportConfig: Pot[AirportConfig],
                              abFeatures: Pot[Seq[ABFeature]],
                              showFeedbackBanner: Pot[Boolean],
                              contactDetails: Pot[ContactDetails],
                              alerts: Pot[List[Alert]]) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("Layout")
    .renderP((_, props: Props) => {
      val layoutModelItemsRCP = SPACircuit.connect { m =>
        LayoutModelItems(m.loggedInUserPot, m.airportConfig, m.abFeatures, m.showFeedbackBanner, m.contactDetails, m.alerts)
      }
      layoutModelItemsRCP { modelProxy =>
        console.log("rendering layout")
        <.div({
          val model = modelProxy()
          val content = for {
            contactDetails <- model.contactDetails
            airportConfig <- model.airportConfig
            user <- model.user
            abFeatures <- model.abFeatures
            showFeedbackBanner <- model.showFeedbackBanner
          } yield {
            val email = contactDetails.supportEmail.getOrElse("drtpoiseteam@homeoffice.gov.uk")
            val aORbTest = abFeatures.headOption.map(_.abVersion).getOrElse("B")
            val (bannerHead, gridItem1, gridItem2, gridItem3) = aORbTest match {
              case "A" => ("Your feedback improves DRT for everyone", 4, 2, 5)
              case _ => ("Help us improve the DRT experience", 4, 2, 5)
            }
            <.div(
              if (showFeedbackBanner) {
                MuiPaper(sx = SxProps(Map("elevation" -> "4", "padding" -> "16px", "margin" -> "20px", "backgroundColor" -> "#0E2560")))(
                  MuiGrid(container = true)(
                    MuiGrid(item = true, xs = gridItem1)(
                      MuiTypography(variant = "h4", sx = SxProps(Map("color" -> "white", "font-weight" -> "bold")))(
                        bannerHead
                      )
                    ),
                    MuiGrid(item = true, xs = gridItem2)(
                      MuiTypography(variant = "h5", sx = SxProps(Map("color" -> "white", "padding" -> "3px 10px")))
                      ("Takes 2 minutes to complete")
                    ),
                    MuiGrid(item = true, xs = gridItem3)(
                      MuiButton(variant = "outlined", sx = SxProps(Map("textTransform" -> "none",
                        "border" -> "1px solid white",
                        "color" -> "white",
                        "font-weight" -> "bold",
                        "font-size" -> buttonTheme.typography.button.fontSize)))(
                        "Give feedback", ^.onClick --> Callback(dom.window.open(s"${SPAMain.urls.rootUrl}/feedback/banner/$aORbTest", "_blank")),
                      )
                    ),
                    MuiGrid(item = true, xs = 1)(
                      <.div(^.style := js.Dictionary("display" -> "flex", "flexDirection" -> "column", "justifyContent" -> "right"),
                        MuiIconButton(sx = SxProps(Map("color" -> "white", "font-weight" -> "bold", "display" -> "flex", "justifyContent" -> "right")))
                        (^.onClick --> Callback(SPACircuit.dispatch(CloseBanner())), ^.aria.label := "Close", Icon.close)
                      ))
                  ))
              } else EmptyVdom,
              <.div(^.className := "topbar",
                <.div(^.className := "main-logo"),
                MuiGrid(container = true)(
                  model.alerts.renderReady { alerts =>
                    if (alerts.nonEmpty) MuiGrid(item = false, xs = 12)(AlertsComponent()) else EmptyVdom
                  },
                  MuiGrid(item = true, xs = 12, sx = SxProps(Map("display" -> "flex", "align-items" -> "center", "justifyContent" -> "right")))(
                    <.div(^.className := "contact",
                      <.span("Contact: ", <.a(^.href := s"mailto:$email", ^.target := "_blank", ^.textDecoration := "underline", email)),
                    )
                  )
                )
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
                              terminal)),
                            PassengerForecastAccuracyComponent(PassengerForecastAccuracyComponent.Props(terminal))
                          )
                        case _ => EmptyVdom
                      },
                    ),
                    <.div(<.div(props.currentLoc.render()))
                  ),
                  VersionUpdateNotice()
                )
              )
            )
          }
          content.getOrElse(<.div("Loading..."))
        })
      }
    }).componentDidMount(_ =>
    Callback(SPACircuit.dispatch(IsNewFeatureAvailable())) >>
      Callback(SPACircuit.dispatch(TrackUser())) >>
      Callback(SPACircuit.dispatch(GetABFeature("feedback"))) >>
      Callback(SPACircuit.dispatch(ShouldViewBanner()))
  ).build

  def apply(ctl: RouterCtl[Loc], currentLoc: Resolution[Loc]): VdomElement = component(Props(ctl, currentLoc))
}
