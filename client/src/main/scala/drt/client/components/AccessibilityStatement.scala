package drt.client.components

import diode.UseValueEq
import drt.client.services.SPACircuit
import drt.client.services.handlers.HideAccessibilityStatement
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiIconButton}
import io.kinoplan.scalajs.react.material.ui.core.system.SxProps
import japgolly.scalajs.react.callback.Callback
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import diode.AnyAction.aType
import drt.client.components.styles.DrtTheme.buttonTheme
import drt.client.modules.GoogleEventTracker
import io.kinoplan.scalajs.react.material.ui.core.MuiButton.Color

import scala.scalajs.js

object AccessibilityStatement {

  case class Props(teamEmail: String, portCode: String) extends UseValueEq

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("UserDashboard")
    .render_P(p => {
      <.div(
        <.div(^.style := js.Dictionary("display" -> "flex", "justifyContent" -> "space-between", "position" -> "sticky",
          "zIndex" -> "1000", "top" -> "0", "background" -> "white", "padding" -> "10px", "borderBottom" -> "1px solid #ddd"),
          <.h1("Accessibility statement for Dynamic Response Tool (DRT)"),
          MuiIconButton(sx = SxProps(Map("color" -> "black")))(^.onClick --> Callback(SPACircuit.dispatch(HideAccessibilityStatement)), Icon.close)
        ),
        <.div(^.style := js.Dictionary("paddingTop" -> "10px"),
          <.ul(<.li(<.a(^.href := "#introduction", "Introduction")),
            <.li(<.a(^.href := "#how-accessible", "How accessible this service is")),
            <.li(<.a(^.href := "#feedback", "Feedback and contact information")),
            <.li(<.a(^.href := "#enforcement", "Enforcement procedure")),
            <.li(<.a(^.href := "#technical-info", "Technical information about this website’s accessibility")),
            <.li(<.a(^.href := "#improve-accessibility", "What we’re doing to improve accessibility"))
          )),
        <.div(
          <.div(^.className := "accessibility-paragraph",
            <.h2(^.id := "introduction", "Introduction"),
            <.p("This accessibility statement applies to ",
              <.a(^.href := "https://drt.homeoffice.gov.uk/", "https://drt.homeoffice.gov.uk/", ^.target := "_blank"), ".",
              "This website is run by Technology Delivery Centre, part of Home Office Digital, Data and Technology Directorate’s Migration and Borders Technology Portfolio, on behalf of Border Force. We want as many people as possible to be able to use this website. For example, that means you should be able to:"
            ),
            <.ul(<.li("change colours, contrast levels and fonts using browser or device settings"),
              <.li("zoom in up to 400% without the text spilling off the screen"),
              <.li("navigate most of the website using a keyboard or speech recognition software"),
              <.li("listen to most of the website using a screen reader (including the most recent versions of JAWS, NVDA and VoiceOver)")),
            <.p("We’ve also made the website text as simple as possible to understand."),
            <.p(<.a(^.href := "https://mcmw.abilitynet.org.uk/", ^.target := "_blank", "AbilityNet"),
              " has advice on making your device easier to use if you have a disability.")
          ),
          <.div(^.className := "accessibility-paragraph",
            <.h2(^.id := "how-accessible", "How accessible this service is"),
            <.p("We know some parts of this website are not fully accessible:"),
            <.ul(
              <.li("you cannot easily navigate content by tabbing through it on some pages"),
              <.li("some words in tables have been shortened"),
              <.li("some elements are problematic for screen reader users")),
          ),
          <.div(^.className := "accessibility-paragraph",
            <.h2(^.id := "feedback", "Feedback and contact information"),
            <.p("The Dynamic Response Tool (DRT) team, which works within Technology Delivery Centre, is responsible for the accessibility of this service. We’re always looking to improve the accessibility of this website. If you find any problems not listed on this page or think we’re not meeting other accessibility requirements, contact us:"),
            MuiButton(color = Color.primary, variant = "contained", size = "large",
              sx = SxProps(Map("textTransform" -> "none",
                "fontSize" -> buttonTheme.typography.button.fontSize)))(
              s"Email us to report a problem",
              ^.className := "btn btn-default",
              ^.href := s"mailto:${p.teamEmail}",
              ^.target := "_blank",
              ^.onClick --> Callback(GoogleEventTracker.sendEvent(p.portCode, "Accessibility", "Email us to report a problem"))
            )
          ),

          <.div(^.className := "accessibility-paragraph",
            <.h2(^.id := "enforcement", "Enforcement procedure"),
            <.p("The Equality and Human Rights Commission (EHRC) is responsible for enforcing the Public Sector Bodies (Websites and Mobile Applications) (No. 2) Accessibility Regulations 2018 (the ‘accessibility regulations’)."),
            <.p("If you’re not happy with how we respond to your complaint, contact the ",
              <.a(^.href := "https://www.equalityadvisoryservice.com/",
                ^.target := "_blank", "Equality Advisory and Support Service (EASS)."))
          ),
          <.div(^.className := "accessibility-paragraph",
            <.h2(^.id := "technical-info", "Technical information about this website’s accessibility"),
            <.p("Border Force is committed to making its websites accessible, in accordance with the Public Sector Bodies (Websites and Mobile Applications) (No. 2) Accessibility Regulations 2018."),
            <.div(
              <.h3(^.id := "compliance-status", "Compliance status"),
              <.p("The website has been tested against the Web Content Accessibility Guidelines (WCAG) 2.1 AA standard."),
              <.p("This website is partially compliant with the Web Content Accessibility Guidelines (WCAG) 2.1 AA standard ",
                <.a(^.href := "https://www.w3.org/TR/WCAG21/",
                  ^.target := "_blank", "(https://www.w3.org/TR/WCAG21/)"), ". The non-compliances are listed below.")
            ),
            <.div(
              <.h3(^.id := "non-compliant-content", "Non-compliant content within the accessibility regulations"),
              <.ul(
                <.li("Landmarks are not used fully. On some pages there are no main headings, multiple h1s, incorrectly labelled sub-headings, empty table headers, missing captions, and no skip links which make it difficult for users to determine where the main content is. This fails WCAG 2.1 success criteria 1.3.1 (Info and Relationships, Level A) and 2.4.1 (Bypass Blocks, Level A)."),
                <.li("Table header descriptions have been shortened, which may not be understandable for all users. This fails WCAG 2.1 success criteria 1.3.1 (Info and Relationships, Level A)."),
                <.li("Table markup used is confusing for screen reader users.When browsing in context using table shortcuts, the relationship between the table and its contents is not clear. This fails WCAG 2.1 criteria 1.3.1 (Info and Relationships, Level A)."),
                <.li("There are unlabelled elements (form, select and text), which are problematic for screen reader users. This fails WCAG 2.1 success criteria 1.3.1 (Info and Relationships, Level A) and 4.1.2 (Name, Role, Value, Level A)."),
                <.li("There are radio buttons whose context is not clear and that are ambiguous for screen reader users browsing out of context as they have not been grouped together appropriately. This fails WCAG 2.1 success. criteria 1.3.1 (Info and Relationships, Level A) and 3.2.2 (On Input, Level A)."),
                <.li("Illogical focus order of radio buttons and select elements will affect keyboard-only users using the tab key to navigate. This fails WCAG 2.1 success criteria 3.2.2 (On Input, Level A) and 2.4.3 (Focus Order, Level A)."),
                <.li("Screen reader users aren’t made aware of modals, and keyboard-only users will experience a loss of focus and must tab through content before gaining focus in a modal. This fails WCAG 2.1 success criteria 2.4.3 (Focus Order, Level A)."),
                <.li("There is no indication that several data export links present download CSV files. This fails WCAG 2.1 success criteria 2.4.4 (Link Purpose - in context, Level A) and 2.4.9 (Link Purpose - Link Only, Level AAA)."),
                <.li("There are custom elements that are not accessible to users of assistive technology. “Plus” and “dash” are announced to screen reader users as the controls, but there is no indication that these are selectable. This fails WCAG 2.1 success criteria (2.1.1 Keyboard, Level A), 4.1.2 (Name, Role, Value, Level A), 1.3.1 (Info and Relationships, Level A) and 2.1.3 (Keyboard - No Exception, Level AAA)."),
                <.li("The Planning page has multiple accessibility problems affecting both keyboard-only and screen reader users, which fail WCAG 2.1 success criteria 1.3.1 (Info and Relationships, Level A) and 4.1.2 (Name, Role, Value, Level A)."),
                <.li("The ‘Close’ link is ambiguous for screen reader users browsing both in and out of context. This element is much more difficult to understand due to focus not being locked within the modal, so some users may feel that ‘Close’ relates to something different on the page. This fails WCAG 2.1 success criteria 2.4.4 (Link Purpose - in context, Level A) and 2.4.9 (Link Purpose - Link Only, Level AAA).")
              )
            )),
          <.div(^.className := "accessibility-paragraph",
            <.h2(^.id := "improve-accessibility", "What we’re doing to improve accessibility"),
            <.p("We  plan to address the above areas of non-compliance with accessibility regulations on this website by ", <.strong("February 2025"), ".")),
          <.div(^.className := "accessibility-paragraph",
            <.h2("Preparation of this accessibility statement"),
            <.p("This statement was prepared on 15 November 2021.")
          )
        )
      )
    })
    .build

  def apply(teamEmail: String, portCode: String): VdomElement = component(Props(teamEmail, portCode))
}
