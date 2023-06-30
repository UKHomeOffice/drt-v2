package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import scalacss.internal.mutable.StyleSheet

case class FormFieldsStyle(common: CommonStyle = DefaultCommonStyle) extends StyleSheet.Inline {

  import dsl._

  val textField = style(
    width(150.px),
    unsafeChild("label")(
      fontSize(1.5.rem)
    )
  )

  val datePicker = style(
    marginBottom(1.2.rem),
    width(150.px),
    padding(4.px, 0.px, 5.px),
    unsafeChild("input")(
      fontSize(1.5.rem)
    )
  )

  val daySelector = style(
    maxWidth(820.px),
  )

  val datePickerLabel = style(
    unsafeChild("label")(
      marginTop(1.rem),
      fontSize(1.2.rem)
    ),
  )

  val textFieldSmall = style(
    width(45.px),
    unsafeChild("label")(
      fontSize(1.5.rem)
    ),
    unsafeChild("p")(
      fontSize(1.rem)
    )
  )

  val regularText = style(
    fontSize :=! 1.2.rem,
    unsafeChild("p")(
      fontSize :=! 1.2.rem
    )
  )

  val formHelperText = style(
    unsafeChild("span")(
      fontSize(1.rem),
      padding(1.rem),
    )
  )

  val labelWide = style(
    width(100.pc)
  )

  val simulation = style(
    padding(15.px),
    minHeight(550.px),
  )

  val simulationCharts = style(
    padding(15.px),
  )

}

object DefaultFormFieldsStyle extends FormFieldsStyle
