package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait ShiftForm extends js.Object {
  var id: Int = js.native
  var name: String = js.native
  var startTime: String = js.native
  var endTime: String = js.native
  var defaultStaffNumber: Int = js.native
}

object ShiftForm {
  def apply(id: Int, name: String, startTime: String, endTime: String, defaultStaffNumber: Int): ShiftForm = {
    val p = (new js.Object).asInstanceOf[ShiftForm]
    p.id = id
    p.name = name
    p.startTime = startTime
    p.endTime = endTime
    p.defaultStaffNumber = defaultStaffNumber
    p
  }
}

@js.native
trait ShiftFormProps extends js.Object {
  var port: String = js.native
  var terminal: String = js.native
  var interval: Int = js.native
  var shiftForms: Array[ShiftForm] = js.native
  var confirmHandler: js.Function1[js.Array[ShiftForm], Unit] = js.native
}

object ShiftFormProps {
  def apply(port: String, terminal: String, interval: Int, initialShifts: Seq[ShiftForm], confirmHandler: Seq[ShiftForm] => Unit): ShiftFormProps = {
    val p = (new js.Object).asInstanceOf[ShiftFormProps]
    p.port = port
    p.terminal = terminal
    p.interval = interval
    p.shiftForms = initialShifts.toArray
    p.confirmHandler = (shiftForms: js.Array[ShiftForm]) => confirmHandler(shiftForms.toSeq)
    p
  }
}

object AddShiftsFormComponent {
  @js.native
  @JSImport("@drt/drt-react", "AddShiftsForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[ShiftFormProps, Children.None](RawComponent)

  def apply(props: ShiftFormProps): VdomElement = {
    component(props)
  }

}
