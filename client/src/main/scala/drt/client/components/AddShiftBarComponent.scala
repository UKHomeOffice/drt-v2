package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IAddShiftBarComponentProps extends js.Object {
  var onClickGetStarted: js.Function0[Unit] = js.native
  var onViewStaffing: js.Function0[Unit] = js.native
}

object IAddShiftBarComponentProps {
  def apply(onClickGetStarted: js.Function0[Unit], onViewStaffing: js.Function0[Unit]): IAddShiftBarComponentProps = {
    val p = (new js.Object).asInstanceOf[IAddShiftBarComponentProps]
    p.onClickGetStarted = onClickGetStarted
    p.onViewStaffing = onViewStaffing
    p
  }
}

object AddShiftBarComponent {
  @js.native
  @JSImport("@drt/drt-react", "AddShiftBar")
  object RawComponent extends js.Object

  val component = JsFnComponent[IAddShiftBarComponentProps, Children.None](RawComponent)

  def apply(props: IAddShiftBarComponentProps): VdomElement = {
    component(props)
  }

}
