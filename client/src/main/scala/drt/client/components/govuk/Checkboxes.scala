package drt.client.components.govuk

import japgolly.scalajs.react.facade.React
import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react.vdom.html_<^.VdomElement
import japgolly.scalajs.react.{ Children, JsFnComponent }

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "Checkboxes")
object RawCheckboxes extends js.Object

@js.native
trait CheckboxOption extends js.Object {
  var value: String = js.native
  var label: React.Node = js.native
  var hint: js.UndefOr[String] = js.native
  var conditional: js.UndefOr[React.Node] = js.native
  var disabled: js.UndefOr[Boolean] = js.native
  var testId: js.UndefOr[String] = js.native
}

object CheckboxOption {
  def apply(
      value: String,
      label: VdomNode,
      hint: Option[String] = None,
      conditional: Option[VdomNode] = None,
      disabled: Option[Boolean] = None,
      testId: Option[String] = None
  ): CheckboxOption = {
    val option = (new js.Object).asInstanceOf[CheckboxOption]
    option.value = value
    option.label = label.rawNode
    hint.foreach(option.hint = _)
    conditional.foreach(content => option.conditional = content.rawNode)
    disabled.foreach(option.disabled = _)
    testId.foreach(option.testId = _)
    option
  }
}

sealed abstract class CheckboxesLegendSize(val value: String)

object CheckboxesLegendSize {
  case object Small extends CheckboxesLegendSize("s")
  case object Medium extends CheckboxesLegendSize("m")
  case object Large extends CheckboxesLegendSize("l")
  case object ExtraLarge extends CheckboxesLegendSize("xl")
}

@js.native
trait CheckboxesProps extends js.Object {
  var name: String = js.native
  var idPrefix: js.UndefOr[String] = js.native
  var options: js.Array[CheckboxOption] = js.native
  var label: js.UndefOr[React.Node] = js.native
  var isPageHeading: js.UndefOr[Boolean] = js.native
  var legendSize: js.UndefOr[String] = js.native
  var hint: js.UndefOr[String] = js.native
  var error: js.UndefOr[String] = js.native
  var value: js.UndefOr[js.Array[String]] = js.native
  var defaultValue: js.UndefOr[js.Array[String]] = js.native
  var onChange: js.UndefOr[js.Function1[js.Array[String], Unit]] = js.native
  var disabled: js.UndefOr[Boolean] = js.native
  var inline: js.UndefOr[Boolean] = js.native
  var small: js.UndefOr[Boolean] = js.native
}

object CheckboxesProps {
  def apply(
      name: String,
      options: js.Array[CheckboxOption],
      idPrefix: js.UndefOr[String] = js.undefined,
      label: js.UndefOr[React.Node] = js.undefined,
      isPageHeading: js.UndefOr[Boolean] = js.undefined,
      legendSize: js.UndefOr[CheckboxesLegendSize] = js.undefined,
      hint: js.UndefOr[String] = js.undefined,
      error: js.UndefOr[String] = js.undefined,
      value: js.UndefOr[js.Array[String]] = js.undefined,
      defaultValue: js.UndefOr[js.Array[String]] = js.undefined,
      onChange: js.UndefOr[js.Function1[js.Array[String], Unit]] = js.undefined,
      disabled: js.UndefOr[Boolean] = js.undefined,
      inline: js.UndefOr[Boolean] = js.undefined,
      small: js.UndefOr[Boolean] = js.undefined
  ): CheckboxesProps = {
    val props = (new js.Object).asInstanceOf[CheckboxesProps]
    props.name = name
    props.idPrefix = idPrefix
    props.options = options
    props.label = label
    props.isPageHeading = isPageHeading
    props.legendSize = legendSize.map(_.value)
    props.hint = hint
    props.error = error
    props.value = value
    props.defaultValue = defaultValue
    props.onChange = onChange
    props.disabled = disabled
    props.inline = inline
    props.small = small
    props
  }
}

object Checkboxes {
  private val component = JsFnComponent[CheckboxesProps, Children.None](RawCheckboxes)

  def apply(props: CheckboxesProps): VdomElement = component(props)
}
