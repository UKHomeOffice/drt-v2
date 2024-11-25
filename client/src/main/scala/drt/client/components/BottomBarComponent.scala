package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport


@js.native
trait BottomBarProps extends js.Object {
  var email: String = js.native
  var onClickAccessibilityStatement: js.Function0[Unit] = js.native
  var url: String = js.native
}

object BottomBarProps {
  def apply(teamEmail: String, onClickAccessibilityStatement: js.Function0[Unit], feedbackUrl: String): BottomBarProps = {
    val p = (new js.Object).asInstanceOf[BottomBarProps]
    p.email = teamEmail
    p.onClickAccessibilityStatement = onClickAccessibilityStatement
    p.url = feedbackUrl
    p
  }
}

object BottomBarComponent {
  @js.native
  @JSImport("@drt/drt-react", "BottomBar")
  object RawComponent extends js.Object

  val component = JsFnComponent[BottomBarProps, Children.None](RawComponent)

  def apply(props: BottomBarProps): VdomElement = {
    component(props)
  }

}