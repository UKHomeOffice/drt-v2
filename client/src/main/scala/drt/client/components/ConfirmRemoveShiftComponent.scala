package drt.client.components

import japgolly.scalajs.react.component.JsFn.Component
import japgolly.scalajs.react.{Children, CtorType, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait RemoveShiftFormProps extends js.Object {
  var shift: ShiftForm = js.native
  var onConfirm: js.Function1[ShiftForm, Unit] = js.native
  var onCancel: js.Function0[Unit] = js.native
}

object RemoveShiftFormProps {
  def apply(shift: ShiftForm,
            removeShiftConfirmHandler: ShiftForm => Unit,
            cancelRemoveShiftHandler: () => Unit
           ): RemoveShiftFormProps = {
    val p = (new js.Object).asInstanceOf[RemoveShiftFormProps]
    p.shift = shift
    p.onConfirm = (shiftForm: ShiftForm) => removeShiftConfirmHandler(shiftForm)
    p.onCancel = () => cancelRemoveShiftHandler()
    p
  }
}

object ConfirmRemoveShiftComponent {
  @js.native
  @JSImport("@drt/drt-react", "ConfirmRemoveShift")
  object RawComponent extends js.Object

  val component: Component[RemoveShiftFormProps, CtorType.Props] = JsFnComponent[RemoveShiftFormProps, Children.None](RawComponent)

  def apply(props: RemoveShiftFormProps): VdomElement = {
    component(props)
  }

}
