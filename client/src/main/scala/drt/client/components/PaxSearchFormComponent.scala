package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement
import scala.scalajs.js
import scala.scalajs.js.Date
import scala.scalajs.js.annotation.JSImport

@js.native
trait PaxSearchFormPayload extends js.Object {
  var day: String
  var time: String
  var arrivalDate: Date
  var fromDate: String
    var toDate: String
  var timeMachine: Boolean
}

@js.native
trait IPaxSearchForm extends PaxSearchFormPayload {
  var onChange: js.Function1[PaxSearchFormPayload, Unit]
}

object IPaxSearchForm {
  def apply(day: String, time: String, arrivalDate: Date, fromDate: String, toDate: String, timeMachine: Boolean, onChange: PaxSearchFormPayload => Unit): IPaxSearchForm = {
    val p = (new js.Object).asInstanceOf[IPaxSearchForm]
    p.day = day
    p.time = time
    p.arrivalDate = arrivalDate
    p.fromDate = fromDate
    p.toDate = toDate
    p.timeMachine = timeMachine
    p.onChange = onChange
    p
  }
}

object PaxSearchFormComponent {
  @js.native
  @JSImport("@drt/drt-react", "PaxSearchForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[IPaxSearchForm, Children.None](RawComponent)

  def apply(props: IPaxSearchForm): VdomElement = {
    component(props)
  }

}