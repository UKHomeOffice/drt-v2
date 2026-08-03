package drt.client.components

import drt.client.SPAMain
import drt.shared.airportconfig.Test
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalajs.dom
import uk.gov.homeoffice.drt.ports.AirportConfig

import scala.scalajs.js

class AccessibilityStatementComponentSpec extends AnyFunSuite with Matchers {

  private def withPortCodeElement[A](portCode: String = "TEST")(test: => A): A = {
    val el = dom.document.createElement("input")
    el.setAttribute("id", "port-code")
    el.setAttribute("value", portCode)
    dom.document.body.appendChild(el)

    try test
    finally dom.document.body.removeChild(el)
  }

  private def accessibilityProps(
      airportConfig: Option[AirportConfig],
      section: Option[String],
      handler: js.Function0[Unit]
  ): IAccessibilityStatementProps = withPortCodeElement() {
    val page = SPAMain.AccessibilityStatementLoc(section)

    IAccessibilityStatementProps(
      teamEmail(airportConfig),
      handler,
      scrollSection(page)
    )
  }

  private def teamEmail(airportConfig: Option[AirportConfig]): String =
    airportConfig.map(_.contactEmail.getOrElse("")).getOrElse("")

  private def selectedPortCode(airportConfig: Option[AirportConfig]): String =
    airportConfig.map(_.portCode.iata).getOrElse("")

  private def scrollSection(page: SPAMain.AccessibilityStatementLoc): String =
    page.section.getOrElse("")

  private def propsFromPage(page: SPAMain.AccessibilityStatementLoc): IAccessibilityStatementProps =
    IAccessibilityStatementProps(teamEmail(Some(Test.config)), () => (), scrollSection(page))

  test("IAccessibilityStatementProps sets defaults and stores values") {
    var invoked = false
    val handler: js.Function0[Unit] = () => invoked = true

    val props = IAccessibilityStatementProps("team@ex.com", handler, "details")

    props.teamEmail shouldBe "team@ex.com"
    props.accessibilityStatementUrl shouldBe "#accessibility"
    props.scrollSection shouldBe "details"

    // calling the JS function should flip the flag
    invoked shouldBe false
    props.sendReportProblemGaEvent()
    invoked shouldBe true
  }

  test("IAccessibilityStatementProps handles empty email and section") {
    val noOp: js.Function0[Unit] = () => ()
    val props = IAccessibilityStatementProps("", noOp, "")

    props.teamEmail shouldBe ""
    props.scrollSection shouldBe ""
    props.accessibilityStatementUrl shouldBe "#accessibility"
  }

  test("props derived from no airport config use empty email") {
    val props = accessibilityProps(None, Some("details"), () => ())

    props.teamEmail shouldBe ""
    props.scrollSection shouldBe "details"
    props.accessibilityStatementUrl shouldBe "#accessibility"
  }

  test("props derived from airport config with no contact email use empty email") {
    val props = accessibilityProps(Some(Test.config.copy(contactEmail = None)), Some("details"), () => ())

    props.teamEmail shouldBe ""
    props.scrollSection shouldBe "details"
  }

  test("props derived from airport config with contact email use that email") {
    val email = "user@example.com"
    val props = accessibilityProps(Some(Test.config.copy(contactEmail = Some(email))), Some("details"), () => ())

    props.teamEmail shouldBe email
    props.scrollSection shouldBe "details"
  }

  test("selectedPortCode returns empty string when airport config is missing") {
    selectedPortCode(None) shouldBe ""
  }

  test("selectedPortCode returns the real iata code from airport config") {
    selectedPortCode(Some(Test.config)) shouldBe Test.config.portCode.iata
  }

  test("props derived from AccessibilityStatementLoc use empty section when page section is missing") {
    withPortCodeElement() {
      val page = SPAMain.AccessibilityStatementLoc(None)
      val props = propsFromPage(page)

      props.scrollSection shouldBe ""
    }
  }

  test("props derived from AccessibilityStatementLoc use the page section when present") {
    withPortCodeElement() {
      val page = SPAMain.AccessibilityStatementLoc(Some("faq"))
      val props = propsFromPage(page)

      props.scrollSection shouldBe "faq"
    }
  }

}
