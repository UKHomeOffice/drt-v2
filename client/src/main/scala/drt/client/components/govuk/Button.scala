package drt.client.components.govuk

import japgolly.scalajs.react.facade.React
import japgolly.scalajs.react.vdom.html_<^.VdomElement
import japgolly.scalajs.react.{ Children, JsFnComponent, ReactEvent }

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "Button")
object RawButton extends js.Object

@js.native
trait ButtonProps extends js.Object {
  var children: React.Node = js.native
  var variant: js.UndefOr[String] = js.native
  var secondaryStyle: js.UndefOr[String] = js.native
  var size: js.UndefOr[String] = js.native
  var onClick: js.UndefOr[js.Function1[ReactEvent, Unit]] = js.native
  var className: js.UndefOr[String] = js.native
}

object ButtonProps {
  def apply(
      children: React.Node,
      variant: js.UndefOr[String] = js.undefined,
      secondaryStyle: js.UndefOr[String] = js.undefined,
      size: js.UndefOr[String] = js.undefined,
      onClick: js.UndefOr[js.Function1[ReactEvent, Unit]] = js.undefined,
      className: js.UndefOr[String] = js.undefined
  ): ButtonProps = {
    val props = (new js.Object).asInstanceOf[ButtonProps]
    props.children = children
    props.variant = variant
    props.secondaryStyle = secondaryStyle
    props.size = size
    props.onClick = onClick
    props.className = className
    props
  }
}

object Button {
  private val component = JsFnComponent[ButtonProps, Children.None](RawButton)

  def apply(props: ButtonProps): VdomElement = component(props)
}
