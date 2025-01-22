package drt.client.components;

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.JSConverters._

@js.native
trait InitialShiftsProps extends js.Object {
  var shifts: js.Array[DefaultShift] = js.native

}

object InitialShiftsProps {
  def apply(shifts: Seq[DefaultShift]): InitialShiftsProps = {
    val p = (new js.Object).asInstanceOf[InitialShiftsProps]
    p.shifts = shifts.toJSArray
    p
  }
}

object ShiftSummaryComponent {
  @js.native
  @JSImport("@drt/drt-react", "ShiftSummary")
  object RawComponent extends js.Object

  val component = JsFnComponent[InitialShiftsProps, Children.None](RawComponent)

  def apply(props: InitialShiftsProps): VdomElement = {
    component(props)
  }

}