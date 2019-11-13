package drt.client.components

import japgolly.scalajs.react.{CtorType, _}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.vdom.html_<^._
import scalacss.DevDefaults._

/**
  * Common Bootstrap components for scalajs-react
  */
object Bootstrap {

  // shorthand for styles
  @inline private def bss: BootstrapStyles.type = GlobalStyles.bootstrapStyles

  // Common Bootstrap contextual styles
  object CommonStyle extends Enumeration {
    val default, primary, success, info, warning, danger = Value
  }

  object Styles extends StyleSheet.Inline {

    import dsl._

    val panelDefaultStr: String = "panel-default"
    val panelDefault: StyleA = style(addClassNames(panelDefaultStr))
  }

  object Panel {
    case class Props(heading: String, style: CommonStyle.Value = CommonStyle.default)

    val component: Component[Props, Unit, Unit, CtorType.PropsAndChildren] = ScalaComponent.builder[Props](displayName = "Panel")
      .renderPC((_, p, c) =>
        <.div(^.className := Styles.panelDefaultStr, //todo style cleanup bss.panelOpt(p.style),
          <.div(^.className := bss.panelHeadingStr, p.heading),
          <.div(^.className := bss.panelBodyStr, c)
        )
      ).build

    def apply(props: Props, children: VdomNode*): Unmounted[Props, Unit, Unit] = component(props)(children: _*)

    def apply(): Component[Props, Unit, Unit, CtorType.PropsAndChildren] = component
  }

}
