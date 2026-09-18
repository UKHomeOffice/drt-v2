package drt.client.components.govuk

import japgolly.scalajs.react.vdom.html_<^.VdomElement
import japgolly.scalajs.react.{ Children, JsFnComponent }

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "DatePicker")
object RawDatePicker extends js.Object

@js.native
trait DatePickerProps extends js.Object {
  var id: String = js.native
  var name: js.UndefOr[String] = js.native
  var label: String = js.native
  var hint: js.UndefOr[String] = js.native
  var value: js.UndefOr[String] = js.native
  var onChange: js.UndefOr[js.Function1[String, Unit]] = js.native
  var required: js.UndefOr[Boolean] = js.native
  var minDate: js.UndefOr[String] = js.native
  var maxDate: js.UndefOr[String] = js.native
}

object DatePickerProps {
  def apply(
      id: String,
      label: String,
      hint: js.UndefOr[String] = js.undefined,
      value: js.UndefOr[String] = js.undefined,
      onChange: js.UndefOr[js.Function1[String, Unit]] = js.undefined,
      name: js.UndefOr[String] = js.undefined,
      required: js.UndefOr[Boolean] = js.undefined,
      minDate: js.UndefOr[String] = js.undefined,
      maxDate: js.UndefOr[String] = js.undefined
  ): DatePickerProps = {
    val p = (new js.Object).asInstanceOf[DatePickerProps]
    p.id = id
    p.label = label
    p.hint = hint
    p.value = value
    p.onChange = onChange
    p.name = name
    p.required = required
    p.minDate = minDate
    p.maxDate = maxDate
    p
  }
}

object DatePicker {
  private val component = JsFnComponent[DatePickerProps, Children.None](RawDatePicker)

  def apply(props: DatePickerProps): VdomElement = component(props)
}
