package drt.client.components

import diode.data.Pot
import drt.client.components.styles.DrtTheme
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.client.services.handlers.GetABFeature
import drt.shared.{ContactDetails, OutOfHoursStatus}
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiGrid, MuiPaper, MuiTypography}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, CtorType, ScalaComponent}
import org.scalajs.dom
import uk.gov.homeoffice.drt.ABFeature

object ContactPage {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ContactUs")
    .render_P(_ =>
      MuiGrid(container = true)(
        MuiGrid(item = true, xs = 6)(
          <.div(ContactDetailsComponent())),
        MuiGrid(item = true, xs = 6)(
          <.div(FeedBackComponent())
        )
      ))
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"contact")
    })
    .build

  def apply(): VdomElement = component(Props())
}

case class ContactModel(contactDetails: Pot[ContactDetails], oohStatus: Pot[OutOfHoursStatus])

object ContactDetailsComponent {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ContactUs")
    .render_P { _ =>
      val contactDetailsRCP = SPACircuit.connect(m => ContactModel(m.contactDetails, m.oohStatus))
      <.div(
        contactDetailsRCP(contactDetailsMP => {
          <.div(contactDetailsMP().contactDetails.renderReady(details => {
            contactDetailsMP().oohStatus.renderReady(oohStatus => {
              val email = details.supportEmail.getOrElse("DRT Support Email Missing")
              val oohPhone = details.oohPhone.getOrElse("OOH Contact Number Missing")
              val contactUsHeader = MuiGrid(item = true, xs = 12)(MuiTypography(sx = SxProps(Map(
                "color" -> DrtTheme.theme.palette.primary.`700`,
                "font-size" -> DrtTheme.theme.typography.h2.fontSize,
                "font-weight" -> DrtTheme.theme.typography.h2.fontWeight
              )))("Contacting the DRT team"))

              val emailHeader = "Email :"
              val officeHourLabel = "Office hours :"
              val officeHours = "Monday to Friday (9 am to 5 pm)"

              val oohMesssage = MuiGrid(container = true, spacing = 2)(
                contactUsHeader,
                MuiGrid(item = true, xs = 2)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("font-weight" -> "bold")))(
                    officeHourLabel
                  )
                ),
                MuiGrid(item = true, xs = 10)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("float" -> "left")))(
                    officeHours
                  )
                ),
                MuiGrid(item = true, xs = 2)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("font-weight" -> "bold")))(
                    emailHeader
                  )
                ),
                MuiGrid(item = true, xs = 10)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("float" -> "left")))(
                    <.a(^.href := s"mailto:$email", email)
                  )
                ),
                MuiGrid(item = true, xs = 6)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("font-weight" -> "bold")))(
                    "Contact number (outside of office hours) :"
                  )
                ),
                MuiGrid(item = true, xs = 6)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("float" -> "left")))(
                    oohPhone
                  )
                ),
              )

              val inHoursMessage = MuiGrid(container = true, spacing = 2)(
                contactUsHeader,
                MuiGrid(item = true, xs = 2)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("font-weight" -> "bold")))(
                    officeHourLabel
                  )
                ),
                MuiGrid(item = true, xs = 10)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("float" -> "left")))(
                    officeHours
                  )
                ),
                MuiGrid(item = true, xs = 2)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("font-weight" -> "bold")))(
                    emailHeader
                  )
                ),
                MuiGrid(item = true, xs = 10)(
                  MuiTypography(variant = "h7", sx = SxProps(Map("float" -> "left")))(
                    <.a(^.href := s"mailto:$email", email)
                  )
                )
              )

              <.div(if (oohStatus.isOoh) oohMesssage else inHoursMessage)
            })
          }))
        })
      )
    }.build

  def apply(): VdomElement = component(Props())

}

case class FeedbackModel(abFeatures: Pot[Seq[ABFeature]])

object FeedBackComponent {

  case class Props()

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("ContactUs")
    .render_P { _ =>
      val feedbackRCP = SPACircuit.connect(m => FeedbackModel(m.abFeatures))
      <.div(
        feedbackRCP(feedbackMP => {
          <.div(
            feedbackMP().abFeatures.renderReady { abFeatures =>
              val aORbTest = abFeatures.headOption.map(_.testType).getOrElse("B")
              val bannerHead = aORbTest == "A" match {
                case true => "Your feedback improves DRT for everyone"
                case false => "Help us improve your DRT experience"
              }
              <.div(
                MuiPaper(sx = SxProps(Map("elevation" -> "4", "padding" -> "16px", "margin" -> "20px", "backgroundColor" -> "#0E2560")))(
                  MuiGrid(container = true, spacing = 2)(
                    MuiGrid(item = true, xs = 12)(
                      MuiTypography(variant = "h4", sx = SxProps(Map("color" -> "white", "font-weight" -> "bold")))(
                        bannerHead
                      )
                    ),
                    MuiGrid(item = true, xs = 12)(
                      MuiTypography(variant = "h7", sx = SxProps(Map("color" -> "white", "float" -> "left", "padding" -> "2px 0")))(
                        "Complete a short survey (approx. 2 minutes)"
                      )
                    ),
                    MuiGrid(item = true, xs = 12)(
                      MuiTypography(variant = "h7", sx = SxProps(Map("color" -> "white", "float" -> "left", "padding" -> "2px 0", "font-weight" -> "bold")))(
                        "Your feedback improves how our data can:"
                      ),
                      MuiTypography(variant = "h7", sx = SxProps(Map("color" -> "white", "float" -> "left", "padding" -> "2px 0")))(
                        <.ul(
                          <.li("support resource planning capability"),
                          <.li("facilitate smoother journeys for legitimate passengers"),
                          <.li("identify potential risks"),
                          <.li("create a more resilient Border")
                        ))
                    ),
                    MuiGrid(item = true, xs = 12)(
                      MuiButton(variant = "outlined", sx = SxProps(Map("border" -> "1px solid white", "color" -> "white", "font-weight" -> "bold")))(
                        "Give feedback >", ^.onClick --> Callback(dom.window.open(s"http://0.0.0.0:3000/feedback/contact-us/$aORbTest", "_blank")),
                      )
                    )
                  )))
            })
        }))
    }.componentDidMount(_ =>
    Callback(SPACircuit.dispatch(GetABFeature("feedback")))
  )
    .build

  def apply(): VdomElement = component(Props())

}
