package drt.client.components;

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.JSConverters._

@js.native
trait ShiftSummaryProps extends js.Object {
  var shiftSummaries: js.Array[ShiftSummary] = js.native
}

object ShiftSummaryProps {
  def apply(shiftSummaries: Seq[ShiftSummary]): ShiftSummaryProps = {
    val p = (new js.Object).asInstanceOf[ShiftSummaryProps]
    p.shiftSummaries = shiftSummaries.toJSArray
    p
  }
}

object ShiftSummaryComponent {
  @js.native
  @JSImport("@drt/drt-react", "ShiftSummaryView")
  object RawComponent extends js.Object

  val component = JsFnComponent[ShiftFormProps, Children.None](RawComponent)

  def apply(props: ShiftFormProps): VdomElement = {
    component(props)
  }

}