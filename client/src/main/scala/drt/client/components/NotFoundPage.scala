package drt.client.components

import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.vdom.html_<^._

object NotFoundPage {
  case class Props()

  val component = ScalaComponent.builder[Props]("NotFoundPage")
    .render { _ =>
      <.div(
        ^.className := "govuk-width-container",
        <.main(
          ^.className := "govuk-main-wrapper govuk-main-wrapper--l",
          ^.id := "main-content",
          ^.role := "main",
          <.div(
            ^.className := "govuk-grid-row",
            <.div(
              ^.className := "govuk-grid-column-two-thirds",
              <.h1(^.className := "govuk-heading-l", "Page not found"),
              <.br(),
              <.br(),
              <.p(^.className := "govuk-body", "If you typed the web address, check it is correct."),
              <.p(^.className := "govuk-body", "If you pasted the web address, check you copied the entire address."),
              <.p(
                ^.className := "govuk-body",
                "If the web address is correct or you selected a link or button, please try again",
                <.br(),
                "or email the DRT team at ",
                <.a(
                  ^.href := "#",
                  ^.className := "govuk-link",
                  ^.textDecoration := "underline",
                  "drtpoiseteam@homeoffice.gov.uk"
                ),
                "."
              )
            )
          )
        )
      )
    }
    .build

  def apply(): VdomElement = component(Props())
}
