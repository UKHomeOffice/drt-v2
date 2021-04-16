package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import scalacss.internal.mutable.StyleSheet

case class FormFieldsStyle(common: CommonStyle = DefaultCommonStyle) extends StyleSheet.Inline {

  import common.theme
  import dsl._

  val container = style(
    display.flex,
    flexWrap.wrap
  )

  val textField = style(
    marginLeft(theme.spacing.unit.px),
    marginRight(theme.spacing.unit.px),
    width(150.px),
    unsafeChild("label")(
      fontSize(1.5.rem)
    )
  )

  val textFieldSmall = style(
    marginLeft(theme.spacing.unit.px),
    marginRight(theme.spacing.unit.px),
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
      fontSize(1.rem)
    )
  )

  val dense = style(
    marginTop(19.px)
  )

  val labelWide = style(
    width(100.pc)
  )

  val simulation = style(
    padding(15.px),
    minHeight(550.px),
  )

  val simulationButtons = style(
    margin(5.px),
    padding(5.px),
  )

  val simulationAccordion = style(
    fontSize(1.rem)
  )

  val buttons = style(
    marginTop(theme.spacing.unit.px)
  )

  val simulationCharts = style(
    padding(15.px),
  )

}

object DefaultFormFieldsStyle extends FormFieldsStyle
