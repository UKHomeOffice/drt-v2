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

//  object Button {
//
//    case class Props(onClick: Callback, style: CommonStyle.Value = CommonStyle.default, addStyles: Seq[StyleA] = Seq())
//
//    val component = ScalaComponent.builder[Props]("Button")
//      .renderPC((_, p, c) =>
//        <.button(bss.buttonOpt(p.style), p.addStyles, ^.tpe := "button", ^.onClick --> p.onClick, c)
//      ).build
//
//    def apply(props: Props, children: VdomNode*) = component(props, children: _*)
//    def apply() = component
//  }

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

//  object Modal {
//
//    // header and footer are functions, so that they can get access to the the hide() function for their buttons
//    case class Props(header: Callback => VdomNode, footer: Callback => VdomNode, closed: Callback, backdrop: Boolean = true,
//                     keyboard: Boolean = true)
//
//    class Backend(t: BackendScope[Props, Unit]) {
//      def hide = Callback {
//        // instruct Bootstrap to hide the modal
//        jQuery(t.getDOMNode).modal("hide")
//      }
//
//      // jQuery event handler to be fired when the modal has been hidden
//      def hidden(e: JQueryEventObject): js.Any = {
//        // inform the owner of the component that the modal was closed/hidden
//        t.props.flatMap(_.closed).runNow()
//      }
//
//      def render(p: Props, c: PropsChildren) = {
//        val modalStyle = bss.modal
//        <.div(^.style := modalStyle.modal, ^.style := modalStyle.fade, ^.role := "dialog", ^.aria.hidden := true,
//          <.div(^.style := modalStyle.dialog,
//            <.div(^.style := modalStyle.content,
//              <.div(^.style := modalStyle.header, p.header(hide)),
//              <.div(^.style := modalStyle.body, c),
//              <.div(^.style := modalStyle.footer, p.footer(hide))
//            )
//          )
//        )
//      }
//    }
//
//    val component = ScalaComponent.builder[Props]("Modal")
//      .renderBackend[Backend]
//      .componentDidMount(scope => Callback {
//        val p = scope.props
//        // instruct Bootstrap to show the modal
//        jQuery(scope.getDOMNode).modal(js.Dynamic.literal("backdrop" -> p.backdrop, "keyboard" -> p.keyboard, "show" -> true))
//        // register event listener to be notified when the modal is closed
//        jQuery(scope.getDOMNode).on("hidden.bs.modal", null, null, scope.backend.hidden _)
//      })
//      .build
//
//    def apply(props: Props, children: VdomElement*) = component(props)(children: _*)
//    def apply() = component
//  }

}
