package drt.client.components

import scalacss.ProdDefaults._

object GlobalStyles extends StyleSheet.Inline {
  import dsl._

  style(unsafeRoot("body")(
    paddingTop(0.px))
  )

  val bootstrapStyles = BootstrapStyles
}
