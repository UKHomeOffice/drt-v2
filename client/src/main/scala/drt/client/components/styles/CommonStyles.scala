package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import io.kinoplan.scalajs.react.material.ui.core.system._
import scalacss.ScalaCssReactImplicits
import scalacss.internal.mutable.StyleSheet

class CommonStyle extends StyleSheet.Inline with ScalaCssReactImplicits {
  def theme: Theme = createTheme(
    options = ThemeOptions(
      typography = TypographyOptions(
        fontSize = 14,
      ),
    )
  )
}

object DefaultCommonStyle extends CommonStyle
