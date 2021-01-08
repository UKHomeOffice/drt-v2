package drt.client.components

import drt.client.logger.{Logger, LoggerFactory}
import japgolly.scalajs.react.Ref.Simple
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Children, JsComponent, Ref}
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
    var content: js.Any = js.native
    var interactive: Boolean = js.native
    var reference: js.Object = js.native
    var theme: String = js.native
    var maxWidth: js.Any = js.native
    var trigger: String = js.native
    var placement: String = js.native
  }

  def props(content: js.Object, interactive: Boolean): Props = {
    val p = (new js.Object).asInstanceOf[Props]

    p.interactive = interactive
    p.content = content
    p.theme = "light-border"
    p.maxWidth = "None"
    p.trigger = "mouseenter"
    p.placement = "top-end"
    p
  }

  val component = JsComponent[Props, Children.Varargs, Null](Tippy)

  def apply[A](content: js.Object, interactive: Boolean, trigger: VdomTagOf[HTMLElement]) =
    component(props(content, interactive))(trigger)

  def apply[A](content: HtmlTag, interactive: Boolean, trigger: VdomTagOf[HTMLElement]) =
    component(props(content.rawElement, interactive))(trigger)

}
