package drt.client.components

import japgolly.scalajs.react.component.JsFn.Component
import japgolly.scalajs.react.{Children, CtorType, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichIterableOnce
import scala.scalajs.js.annotation.JSImport

@js.native
trait ShiftForm extends js.Object {
  var id: Int = js.native
  var name: String = js.native
  var startTime: String = js.native
  var endTime: String = js.native
  var defaultStaffNumber: Int = js.native
  var startDate: ShiftDate = js.native
}

object ShiftForm {
  def apply(id: Int, name: String, startTime: String, endTime: String, defaultStaffNumber: Int, startDate: ShiftDate): ShiftForm = {
    val p = (new js.Object).asInstanceOf[ShiftForm]
    p.id = id
    p.name = name
    p.startTime = startTime
    p.endTime = endTime
    p.defaultStaffNumber = defaultStaffNumber
    p.startDate = startDate
    p
  }
}

@js.native
trait ShiftFormProps extends js.Object {
  var port: String = js.native
  var terminal: String = js.native
  var interval: Int = js.native
  var shiftForms: js.Array[ShiftForm] = js.native
  var confirmHandler: js.Function1[js.Array[ShiftForm], Unit] = js.native
  var formMode: String = js.native
  var disableAdd: Boolean = js.native
}

object ShiftFormProps {
  def apply(port: String,
            terminal: String,
            interval: Int,
            initialShifts: Seq[ShiftForm],
            confirmHandler: Seq[ShiftForm] => Unit,
            formMode: String,
            disableAdd: Boolean
           ): ShiftFormProps = {
    val p = (new js.Object).asInstanceOf[ShiftFormProps]
    p.port = port
    p.terminal = terminal
    p.interval = interval
    p.shiftForms = initialShifts.toJSArray
    p.confirmHandler = (shiftForms: js.Array[ShiftForm]) => confirmHandler(shiftForms.toSeq)
    p.formMode = formMode
    p.disableAdd = disableAdd
    p
  }
}

object ShiftsFormComponent {
  @js.native
  @JSImport("@drt/drt-react", "ShiftsForm")
  object RawComponent extends js.Object

  val component: Component[ShiftFormProps, CtorType.Props] = JsFnComponent[ShiftFormProps, Children.None](RawComponent)

  def apply(props: ShiftFormProps): VdomElement = {
    component(props)
  }

}
