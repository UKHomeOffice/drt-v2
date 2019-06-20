package drt.client.components

import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object ContactPage {

  case class Props()

  val component = ScalaComponent.builder[Props]("ContactUs")
    .render_P(_ =>
      <.div(^.className := "contact-us", <.h3("Contact Us"), ContactDetails())
    )
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"contact")
    })
    .build

  def apply(): VdomElement = component(Props())
}

object ContactDetails {

  case class Props()

  val component = ScalaComponent.builder[Props]("ContactUs")
    .render_P(p => {
      val contactDetailsRCP = SPACircuit.connect(_.contactDetails)
      <.div(
        contactDetailsRCP(contactDetailsMP => {
          <.div(contactDetailsMP().renderReady(
            details => {
              val email = details.supportEmail.getOrElse("")
              <.div(
                <.p(<.strong("During office Hours"), " (9am to 5pm Monday to Friday)"),
                <.p(s"Contact the Dynamic Response Tool service team by email at ", <.strong(email)),
                details.oohPhone.map(oohPhone =>
                  List(
                    <.p(<.strong("Outside office hours"), " (after 5pm Monday to Friday, all day Saturday, Sunday and Bank Holidays)"),
                    <.p(s"Contact our out of hours support team on ", <.strong(oohPhone), ". Say that you're calling about Dynamic Response Tool.")).toTagMod
                ).getOrElse("")
              )
            }))
        })
      )
    })
    .build

  def apply(): VdomElement = component(Props())

}
