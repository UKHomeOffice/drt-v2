package drt.client.components.styles

import drt.client.components.styles.CssSettings._
import io.kinoplan.scalajs.react.material.ui.core.colors
import io.kinoplan.scalajs.react.material.ui.core.styles.{PaletteOptions, ThemeOptions, TypographyOptions, createMuiTheme}
import scalacss.internal.mutable.StyleSheet


class CommonStyle extends StyleSheet.Inline {

  def theme = createMuiTheme(
    options = ThemeOptions(
      typography = TypographyOptions(useNextVariants = true),
      palette = PaletteOptions(primary = colors.blue)
    )
  )
}

object DefaultCommonStyle extends CommonStyle
