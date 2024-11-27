package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IAccessibilityStatementProps extends js.Object {
  var teamEmail: String = js.native
  var sendReportProblemGaEvent: js.Function0[Unit] = js.native
  var scrollSection: String = js.native
  var url: String = js.native
}

object IAccessibilityStatementProps {
  def apply(teamEmail: String, emailUsToReportAProblemHandler: js.Function0[Unit], scrollSection: String): IAccessibilityStatementProps = {
    val p = (new js.Object).asInstanceOf[IAccessibilityStatementProps]
    p.url = "#accessibility/"
    p.teamEmail = teamEmail
    p.sendReportProblemGaEvent = emailUsToReportAProblemHandler
    p.scrollSection = scrollSection
    p
  }
}

object AccessibilityStatementComponent {
  @js.native
  @JSImport("@drt/drt-react", "AccessibilityStatement")
  object RawComponent extends js.Object

  val component = JsFnComponent[IAccessibilityStatementProps, Children.None](RawComponent)

  def apply(props: IAccessibilityStatementProps): VdomElement = {
    component(props)
  }

}
