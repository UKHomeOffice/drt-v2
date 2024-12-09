package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait Shift extends js.Object {
  var id: Int = js.native
  var name: String = js.native
  var startTime: String = js.native
  var endTime: String = js.native
  var defaultStaffNumber: Int = js.native
}

object Shift {
  def apply(id: Int, name: String, startTime: String, endTime: String, defaultStaffNumber: Int): Shift = {
    val p = (new js.Object).asInstanceOf[Shift]
    p.id = id
    p.name = name
    p.startTime = startTime
    p.endTime = endTime
    p.defaultStaffNumber = defaultStaffNumber
    p
  }
}

@js.native
trait ShiftsProps extends js.Object {
  var port: String = js.native
  var terminal: String = js.native
  var interval: Int = js.native
  var initialShifts: Array[Shift] = js.native
  var confirmHandler: js.Function1[js.Array[Shift], Unit] = js.native
}

object ShiftsProps {
  def apply(port: String, terminal: String, interval: Int, initialShifts: Seq[Shift], confirmHandler: Seq[Shift] => Unit): ShiftsProps = {
    val p = (new js.Object).asInstanceOf[ShiftsProps]
    p.port = port
    p.terminal = terminal
    p.interval = interval
    p.initialShifts = initialShifts.toArray
    p.confirmHandler = (shifts: js.Array[Shift]) => confirmHandler(shifts.toSeq)
    p
  }
}

object AddShiftFormComponent {
  @js.native
  @JSImport("@drt/drt-react", "AddShiftForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[ShiftsProps, Children.None](RawComponent)

  def apply(props: ShiftsProps): VdomElement = {
    component(props)
  }

}
