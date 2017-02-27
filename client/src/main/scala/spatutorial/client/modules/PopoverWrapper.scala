package spatutorial.client.modules

import japgolly.scalajs.react.{React, ReactComponentU_, ReactNode}

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

  def apply(children: ReactNode*) = {
    val f = React.asInstanceOf[js.Dynamic].createFactory(js.Dynamic.global.Bundle.popover.Popover) // access real js component , make sure you wrap with createFactory (this is needed from 0.13 onwards)
    f(toJS, children.toJsArray).asInstanceOf[ReactComponentU_]
  }
}
