package drt.client.modules

import japgolly.scalajs.react.vdom.VdomNode
import japgolly.scalajs.react._
import japgolly.scalajs.react.raw.React

import scala.scalajs.js

case class PopoverWrapper(
                           position: String = "right",
                           className: String = "flights-popover",
                           trigger: String
                         ) {
  def toJS = {
    js.Dynamic.literal(
      position = position,
      className = className,
      trigger = trigger
    )
  }

  def apply(children: VdomNode*) = {
    val f = React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.popover.Popover) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
    f(toJS, children)
  }
}
