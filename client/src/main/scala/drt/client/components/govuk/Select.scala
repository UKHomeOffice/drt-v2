package drt.client.components.govuk

import japgolly.scalajs.react.facade.React
import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react.vdom.html_<^.VdomElement
import japgolly.scalajs.react.{ Children, JsFnComponent }

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("@drt/drt-react", "Select")
object RawSelect extends js.Object

@js.native
trait SelectOption extends js.Object {
  var value: String = js.native
  var label: React.Node = js.native
}

object SelectOption {
  def apply(value: String, label: VdomNode): SelectOption = {
    val option = (new js.Object).asInstanceOf[SelectOption]
    option.value = value
    option.label = label.rawNode
    option
  }
}

@js.native
trait SelectProps extends js.Object {
  var name: String = js.native
  var id: js.UndefOr[String] = js.native
  var options: js.Array[SelectOption] = js.native
  var label: js.UndefOr[React.Node] = js.native
  var ariaLabel: js.UndefOr[String] = js.native
  var labelClassName: js.UndefOr[String] = js.native
  var hint: js.UndefOr[String] = js.native
  var error: js.UndefOr[String] = js.native
  var value: js.UndefOr[String] = js.native
  var defaultValue: js.UndefOr[String] = js.native
  var disabled: js.UndefOr[Boolean] = js.native
  var onChange: js.UndefOr[js.Function1[String, Unit]] = js.native
  var className: js.UndefOr[String] = js.native
}

object SelectProps {
  def withVisibleLabel(
      name: String,
      options: js.Array[SelectOption],
      label: VdomNode,
      id: js.UndefOr[String] = js.undefined,
      labelClassName: js.UndefOr[String] = js.undefined,
      hint: js.UndefOr[String] = js.undefined,
      error: js.UndefOr[String] = js.undefined,
      value: js.UndefOr[String] = js.undefined,
      defaultValue: js.UndefOr[String] = js.undefined,
      disabled: js.UndefOr[Boolean] = js.undefined,
      onChange: js.UndefOr[js.Function1[String, Unit]] = js.undefined,
      className: js.UndefOr[String] = js.undefined
  ): SelectProps =
    build(
      name = name,
      options = options,
      id = id,
      label = label.rawNode,
      labelClassName = labelClassName,
      hint = hint,
      error = error,
      value = value,
      defaultValue = defaultValue,
      disabled = disabled,
      onChange = onChange,
      className = className
    )

  def withAriaLabel(
      name: String,
      options: js.Array[SelectOption],
      ariaLabel: String,
      id: js.UndefOr[String] = js.undefined,
      hint: js.UndefOr[String] = js.undefined,
      error: js.UndefOr[String] = js.undefined,
      value: js.UndefOr[String] = js.undefined,
      defaultValue: js.UndefOr[String] = js.undefined,
      disabled: js.UndefOr[Boolean] = js.undefined,
      onChange: js.UndefOr[js.Function1[String, Unit]] = js.undefined,
      className: js.UndefOr[String] = js.undefined
  ): SelectProps =
    build(
      name = name,
      options = options,
      id = id,
      ariaLabel = ariaLabel,
      hint = hint,
      error = error,
      value = value,
      defaultValue = defaultValue,
      disabled = disabled,
      onChange = onChange,
      className = className
    )

  private def build(
      name: String,
      options: js.Array[SelectOption],
      id: js.UndefOr[String] = js.undefined,
      label: js.UndefOr[React.Node] = js.undefined,
      ariaLabel: js.UndefOr[String] = js.undefined,
      labelClassName: js.UndefOr[String] = js.undefined,
      hint: js.UndefOr[String] = js.undefined,
      error: js.UndefOr[String] = js.undefined,
      value: js.UndefOr[String] = js.undefined,
      defaultValue: js.UndefOr[String] = js.undefined,
      disabled: js.UndefOr[Boolean] = js.undefined,
      onChange: js.UndefOr[js.Function1[String, Unit]] = js.undefined,
      className: js.UndefOr[String] = js.undefined
  ): SelectProps = {
    val props = (new js.Object).asInstanceOf[SelectProps]
    props.name = name
    props.id = id
    props.options = options
    props.label = label
    props.ariaLabel = ariaLabel
    props.labelClassName = labelClassName
    props.hint = hint
    props.error = error
    props.value = value
    props.defaultValue = defaultValue
    props.disabled = disabled
    props.onChange = onChange
    props.className = className
    props
  }
}

object Select {
  private val component = JsFnComponent[SelectProps, Children.None](RawSelect)

  def apply(props: SelectProps): VdomElement = component(props)
}
