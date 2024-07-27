package drt.client.components.styles

import drt.client.components.styles.ScalaCssImplicits.CssSettings._
import io.kinoplan.scalajs.react.material.ui.core.colors.ColorPartial
import io.kinoplan.scalajs.react.material.ui.core.system._
import scalacss.ScalaCssReactImplicits
import scalacss.internal.mutable.StyleSheet

object DrtTheme extends StyleSheet.Inline with ScalaCssReactImplicits {
  private val typographyOptions: TypographyOptions = TypographyOptions(
    fontSize = 16,
    htmlFontSize = 10,
    button = TypographyStyleOptions(textTransform = ""),
    fontFamily = "Arial",
    h1 = TypographyStyleOptions(
      fontSize = "46px",
      fontWeight = "bold",
    ),
    h2 = TypographyStyleOptions(
      fontSize = "40px",
      fontWeight = "bold",
    ),
    h3 = TypographyStyleOptions(
      fontSize = "28px",
      fontWeight = "bold",
    ),
    h4 = TypographyStyleOptions(
      fontSize = "24px",
      fontWeight = "bold",
    ),
    h5 = TypographyStyleOptions(
      fontSize = "18px",
      fontWeight = "bold",
    ),
    subtitle1 = TypographyStyleOptions(
      fontSize = "19px",
      fontWeight = "bold",
    ),
    subtitle2 = TypographyStyleOptions(
      fontSize = "16px",
      fontWeight = "bold",
    ),
    body1 = TypographyStyleOptions(
      fontSize = "14px",
    ),
    body2 = TypographyStyleOptions(
      fontSize = "12px",
    ),
  )

  def theme: Theme = {
    createTheme(
      options = ThemeOptions(
        typography = typographyOptions,
        spacing = 8d,
        palette = PaletteOptions(
          primary = ColorPartial(
            `50` = "#E6E9F1",
            `100` = "#C0C7DE",
            `300` = "#7283B2",
            `400` = "#5269A5",
            `500` = "#334F96",
            `600` = "#2B478D",
            `700` = "#233E82",
            `900` = "#0E2560",
          ),
          grey = ColorPartial(
            `100` = "#F3F5F9",
            `300` = "#B4B5BE",
            `400` = "#547A00",
            `500` = "#777A86",
            `700` = "#404252",
            `900` = "#111224",
          )
        ),
      )
    )
  }

  def buttonSelectedTheme: Theme = createTheme(
    options = ThemeOptions(
      typography = typographyOptions,
      palette = PaletteOptions(
        primary = ColorPartial(`500` = "#0E2560"),
      )
    )
  )

  def buttonTheme: Theme = createTheme(
    options = ThemeOptions(
      typography = typographyOptions,
      palette = PaletteOptions(
        primary = ColorPartial(`500` = "#335096"),
      )
    )
  )

  def tooltipTheme: Theme = createTheme(
    options = ThemeOptions(
      typography = TypographyOptions(
        fontSize = 18,
        htmlFontSize = 10,
        fontFamily = "Arial",
      ),
    )
  )
}
