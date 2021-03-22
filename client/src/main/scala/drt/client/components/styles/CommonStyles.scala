package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import io.kinoplan.scalajs.react.material.ui.core.colors
import io.kinoplan.scalajs.react.material.ui.core.styles._
import scalacss.ScalaCssReactImplicits
import scalacss.internal.mutable.StyleSheet

class CommonStyle extends StyleSheet.Inline with ScalaCssReactImplicits {

  import ScalaCssImplicits._
  import dsl._

  def theme = createMuiTheme(
    options = ThemeOptions(
      typography = TypographyOptions(useNextVariants = true),
      palette = PaletteOptions(primary = colors.blue)
    )
  )
}

object DefaultCommonStyle extends CommonStyle
