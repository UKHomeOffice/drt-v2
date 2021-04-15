package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import scalacss.internal.mutable.StyleSheet

case class ToolTipsStyle(common: CommonStyle = DefaultCommonStyle) extends StyleSheet.Inline {

  import common.theme
  import dsl._

  val triggerHoverIndicator = style(
    cursor.pointer,
    &.hover(opacity(0.75))
  )

}

object DefaultToolTipsStyle extends ToolTipsStyle
