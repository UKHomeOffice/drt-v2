package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import scalacss.internal.mutable.StyleSheet

case class FormFieldsStyle(common: CommonStyle = DefaultCommonStyle) extends StyleSheet.Inline {

  import dsl._

  val simulation = style(
    padding(15.px),
    minHeight(550.px),
  )

  val simulationCharts = style(
    padding(15.px),
  )

}

object DefaultFormFieldsStyle extends FormFieldsStyle
