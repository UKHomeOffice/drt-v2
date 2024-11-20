package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IAccessibilityStatementProps extends js.Object {
  var teamEmail: String = js.native
  var emailUsToReportAProblem: js.Function0[Unit] = js.native
  var onClose: js.Function0[Unit] = js.native
}

object IAccessibilityStatementProps {
  def apply(teamEmail: String, emailUsToReportAProblemHandler: js.Function0[Unit], closeHandler: js.Function0[Unit]): IAccessibilityStatementProps = {
    val p = (new js.Object).asInstanceOf[IAccessibilityStatementProps]
    p.teamEmail = teamEmail
    p.emailUsToReportAProblem = emailUsToReportAProblemHandler
    p.onClose = closeHandler
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
