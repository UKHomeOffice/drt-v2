package drt.client.components.govuk

import japgolly.scalajs.react.facade.React
import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react.vdom.html_<^.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "Radios")
object RawRadios extends js.Object

@js.native
trait RadiosProps extends js.Object {
  var name: String = js.native
  var idPrefix: js.UndefOr[String] = js.native
  var options: js.Array[js.Any] = js.native
  var label: js.UndefOr[React.Node] = js.native
  var isPageHeading: js.UndefOr[Boolean] = js.native
  var legendSize: js.UndefOr[String] = js.native
  var hint: js.UndefOr[String] = js.native
  var error: js.UndefOr[String] = js.native
  var value: js.UndefOr[String] = js.native
  var defaultValue: js.UndefOr[String] = js.native
  var onChange: js.UndefOr[js.Function1[String, Unit]] = js.native
  var inline: js.UndefOr[Boolean] = js.native
  var small: js.UndefOr[Boolean] = js.native
  var disabled: js.UndefOr[Boolean] = js.native
}

object RadiosProps {
  def apply(
             name: String,
             idPrefix: js.UndefOr[String] = js.undefined,
             options: js.Array[js.Any],
             label: js.UndefOr[React.Node] = js.undefined,
             isPageHeading: js.UndefOr[Boolean] = js.undefined,
             legendSize: js.UndefOr[String] = js.undefined,
             hint: js.UndefOr[String] = js.undefined,
             error: js.UndefOr[String] = js.undefined,
             value: js.UndefOr[String] = js.undefined,
             defaultValue: js.UndefOr[String] = js.undefined,
             onChange: js.UndefOr[js.Function1[String, Unit]] = js.undefined,
             inline: js.UndefOr[Boolean] = js.undefined,
             small: js.UndefOr[Boolean] = js.undefined,
             disabled: js.UndefOr[Boolean] = js.undefined
           ): RadiosProps = {
    val p = (new js.Object).asInstanceOf[RadiosProps]
    p.name = name
    p.idPrefix = idPrefix
    p.options = options
    p.label = label
    p.isPageHeading = isPageHeading
    p.legendSize = legendSize
    p.hint = hint
    p.error = error
    p.value = value
    p.defaultValue = defaultValue
    p.onChange = onChange
    p.inline = inline
    p.small = small
    p.disabled = disabled
    p
  }
}

case class RadioOption(
                        value: String,
                        label: VdomNode,
                        hint: Option[String] = None,
                        conditional: Option[VdomNode] = None,
                        disabled: Boolean = false
                      ) {
  def toJs: js.Object = {
    val dict = js.Dictionary[js.Any](
      "value" -> value,
      "label" -> label.rawNode.asInstanceOf[js.Any],
      "disabled" -> disabled
    )
    hint.foreach(h => dict("hint") = h)
    conditional.foreach(c => dict("conditional") = c.rawNode.asInstanceOf[js.Any])
    dict.asInstanceOf[js.Object]
  }
}

case class RadioDivider(text: String = "or") {
  def toJs: js.Object = js.Dictionary("divider" -> text).asInstanceOf[js.Object]
}

object Radios {
  private val component = JsFnComponent[RadiosProps, Children.None](RawRadios)

  def apply(props: RadiosProps): VdomElement = component(props)
}
