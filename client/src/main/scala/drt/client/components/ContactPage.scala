package drt.client.components

import diode.data.Pot
import drt.client.modules.GoogleEventTracker
import drt.client.services.SPACircuit
import drt.shared.{ContactDetails, OutOfHoursStatus}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}

object ContactPage {

  case class Props()

  val component = ScalaComponent.builder[Props]("ContactUs")
    .render_P(_ =>
      <.div(^.className := "contact-us", <.h3("Contact Us"), ContactDetailsComponent())
    )
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"contact")
    })
    .build

  def apply(): VdomElement = component(Props())
}

case class ContactModel(contactDetails: Pot[ContactDetails], oohStatus: Pot[OutOfHoursStatus])

object ContactDetailsComponent {

  case class Props()

  val component = ScalaComponent.builder[Props]("ContactUs")
    .render_P(p => {
      val contactDetailsRCP = SPACircuit.connect(m => ContactModel(m.contactDetails, m.oohStatus))
      <.div(
        contactDetailsRCP(contactDetailsMP => {
          <.div(contactDetailsMP().contactDetails.renderReady(
            details => {
              contactDetailsMP().oohStatus.renderReady(oohStatus => {
                val email = details.supportEmail.getOrElse("DRT Support Email Missing")
                val oohPhone = details.oohPhone.getOrElse("OOH Contact Number Missing")

                val oohMesssage = List(
                  <.p(<.strong("After 5pm Monday to Friday, all day Saturday, Sunday and Bank Holidays")),
                  <.p(s" For urgent issues contact our out of hours support team on ", <.strong(oohPhone), ". Say that you're calling about Dynamic Response Tool."),
                  <.p("For anything else, please email us at ", <.strong(email), " and we'll respond on the next business day.")
                ).toTagMod


                val inHoursMessage = List(
                  <.p(<.strong("9am to 5pm Monday to Friday")),
                  <.p(s"Contact the Dynamic Response Tool service team by email at ", <.strong(email), ".")
                ).toTagMod

                <.div(if (oohStatus.isOoh) oohMesssage else inHoursMessage)
              }
              )
            }
          ))
        })
      )
    })
    .build

  def apply(): VdomElement = component(Props())

}
