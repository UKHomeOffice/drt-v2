package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.Ref.Simple
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Children, JsComponent, Ref}
import org.scalajs.dom.html
import org.scalajs.dom.html.{Div, Input}
import org.scalajs.dom.raw.HTMLElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object TippyJSComponent {

  val log: Logger = LoggerFactory.getLogger("TippyJSComponent")

  @JSImport("@tippyjs/react", JSImport.Default)
  @js.native
  private object Tippy extends js.Object

  val elementRef: Simple[HTMLElement] = Ref[HTMLElement]

  @js.native
  trait Props extends js.Object {
    var content: String = js.native
    var interactive: Boolean = js.native
    
  }

  def props(content: String, interactive: Boolean): Props = {
    val p = (new js.Object).asInstanceOf[Props]

    p.content = content
    p.interactive = interactive
//    p.reference = ref.raw
    p
  }

  //  val component: Component[Props, HTMLElement, CtorType.PropsAndChildren] = JsForwardRefComponent[Props, Children.Varargs, HTMLElement](Tippy)
  val component = JsComponent[Props, Children.Varargs, Null](Tippy)

  def apply(content: String, interactive: Boolean, trigger: VdomTagOf[HTMLElement]) = {

//    trigger.withRef(elementRef)
    component(props(content, interactive))(trigger)
  }

}
