package drt.client.components.govuk

import japgolly.scalajs.react.Callback
import japgolly.scalajs.react.ReactEventFromInput
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react.vdom.html_<^._

object AppRadioGroup {
  case class RadioItem(
      value: String,
      label: VdomNode,
      hint: Option[String] = None,
      id: Option[String] = None
  )

  case class Props(
      name: String,
      legend: String,
      selectedValue: String,
      items: Seq[RadioItem],
      onChange: String => Callback,
      inline: Boolean = true,
      small: Boolean = false,
      describedBy: Option[String] = None
  )

  private def inputId(groupName: String, value: String): String =
    s"$groupName-$value".replace(" ", "-").toLowerCase

  def apply(props: Props): VdomElement = {
    <.fieldset(
      ^.className := "govuk-fieldset",
      <.legend(^.className := "govuk-fieldset__legend govuk-fieldset__legend--m", props.legend),
      <.div(
        ^.className := {
          val inlineClass = Option.when(props.inline)(" govuk-radios--inline").getOrElse("")
          val smallClass = Option.when(props.small)(" govuk-radios--small").getOrElse("")
          s"govuk-radios$inlineClass$smallClass drt-govuk-radios-poc"
        },
        props.items.toTagMod { item =>
          val id = item.id.getOrElse(inputId(props.name, item.value))
          val hintId = s"$id-hint"
          val describedBy = (props.describedBy.toSeq ++ item.hint.map(_ => hintId)).mkString(" ")

          <.div(
            ^.className := "govuk-radios__item",
            <.input(
              ^.className := "govuk-radios__input",
              ^.`type` := "radio",
              ^.id := id,
              ^.name := props.name,
              ^.value := item.value,
              ^.checked := props.selectedValue == item.value,
              Option.when(describedBy.nonEmpty)(^.aria.describedBy := describedBy).whenDefined,
              ^.onChange ==> ((e: ReactEventFromInput) => props.onChange(e.target.value))
            ),
            <.label(^.className := "govuk-label govuk-radios__label", ^.`for` := id, item.label),
            item.hint.whenDefined { hint =>
              <.div(^.id := hintId, ^.className := "govuk-hint govuk-radios__hint", hint)
            }
          )
        }
      )
    )
  }
}





