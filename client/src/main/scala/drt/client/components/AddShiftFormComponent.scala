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
  var interval: Int = js.native
  var initialShifts: Array[Shift] = js.native
}

object ShiftsProps {
  def apply(interval: Int, initialShifts: Seq[Shift]): ShiftsProps = {
    val p = (new js.Object).asInstanceOf[ShiftsProps]
    p.interval = interval
    p.initialShifts = initialShifts.toArray
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
