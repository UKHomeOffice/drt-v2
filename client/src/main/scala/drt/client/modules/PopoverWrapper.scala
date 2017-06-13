package drt.client.modules

import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react._
import japgolly.scalajs.react.raw.React

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSImport, JSName}


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
  val component = JsComponent[Props, Children.Varargs, Null](RawComponent)
//  def apply(trigger: String, position: String, className: String) = component(props(trigger, position, className))_
}

//case class PopoverWrapper(
//                           position: String = "right",
//                           className: String = "flights-popover",
//                           trigger: String
//                         ) {
//  def toJS = {
//    js.Dynamic.literal(
//      position = position,
//      className = className,
//      trigger = trigger
//    )
//  }
//
//  def apply(children: VdomNode*) = {
//    val f = React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.popover.Popover) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
//    f(toJS, children)
//  }
//}
