package drt.client.components.styles
import scalacss.internal.mutable.StyleSheet
import ScalaCssImplicits.CssSettings._


case class ScenarioSimulationStyle(common: CommonStyle = DefaultCommonStyle) extends StyleSheet.Inline {

  import dsl._

  val container = style(
    unsafeChild("label")(
      fontSize(1.5.rem)
    )
  )

}

object DefaultScenarioSimulationStyle extends ScenarioSimulationStyle
