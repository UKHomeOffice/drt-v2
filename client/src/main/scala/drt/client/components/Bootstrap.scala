package drt.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._

import scala.language.implicitConversions
import scala.scalajs.js
import scalacss.DevDefaults._

/**
  * Common Bootstrap components for scalajs-react
  */
object Bootstrap {

  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  @js.native
  trait BootstrapJQuery extends JQuery {
    def modal(action: String): BootstrapJQuery = js.native

    def modal(options: js.Any): BootstrapJQuery = js.native
  }

  implicit def jq2bootstrap(jq: JQuery): BootstrapJQuery = jq.asInstanceOf[BootstrapJQuery]

  // Common Bootstrap contextual styles
  object CommonStyle extends Enumeration {
    val default, primary, success, info, warning, danger = Value
  }

  object Styles extends StyleSheet.Inline {

    import dsl._

    val panelDefaultStr = "panel-default"
    val panelDefault = style(addClassNames(panelDefaultStr))
  }

  object Panel {

    case class Props(heading: String, style: CommonStyle.Value = CommonStyle.default)

    val component = ScalaComponent.builder[Props]("Panel")
      .renderPC((_, p, c) =>
        <.div(^.className := Styles.panelDefaultStr, //todo style cleanup bss.panelOpt(p.style),
          <.div(^.className := bss.panelHeadingStr, p.heading),
          <.div(^.className := bss.panelBodyStr, c)
        )
      ).build

    def apply(props: Props, children: VdomNode*) = component(props)(children: _*)

    def apply() = component
  }

}
