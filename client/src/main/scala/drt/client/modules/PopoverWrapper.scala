package drt.client.modules

import japgolly.scalajs.react.{CtorType, _}
import japgolly.scalajs.react.component.Js.Component

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport


object PopoverWrapper {
//  @JSName("Popover")
  @JSImport("@terebentina/react-popover", "Popover")
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var position: String = js.native
    var trigger: String = js.native
    var className: String = js.native
  }

  def props(trigger: String,
            position: String = "right",
            className: String = "flights-popover"): Props = {
    val p = (new js.Object).asInstanceOf[Props]
    p.position = position
    p.trigger = trigger
    p.className = className
    p
  }
  val component: Component[Props, Null, CtorType.PropsAndChildren] = JsComponent[Props, Children.Varargs, Null](RawComponent)
}
