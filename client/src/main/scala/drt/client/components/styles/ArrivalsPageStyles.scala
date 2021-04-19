package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import scalacss.internal.ValueT.TypedAttrBase.`Be the attr you were born to be!`
import scalacss.internal.mutable.StyleSheet

case class ArrivalsPageStyles(common: CommonStyle = DefaultCommonStyle) extends StyleSheet.Inline {

  import common.theme
  import dsl._

  val redListCountryField = style(
    borderBottom(solid, c"#ff9999", 3 px),
    paddingBottom(5 px),
    display.block
  )

}

object ArrivalsPageStylesDefault extends ArrivalsPageStyles
