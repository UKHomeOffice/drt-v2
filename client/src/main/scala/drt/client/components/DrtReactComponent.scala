package drt.client.components


import japgolly.scalajs.react.{Children, _}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object DrtReactComponent {

  @js.native
  trait InputProps extends js.Object {
    var `type`: js.UndefOr[String] = js.native
  }

  object InputProps {
    def apply(`type`: String): InputProps = {
      val p = js.Dynamic.literal().asInstanceOf[InputProps]
      p.`type` = `type`
      p
    }
  }

  @js.native
  @JSImport("drt-react", "Input")
  object Input extends js.Function

  val component = JsFnComponent[InputProps, Children.None](Input)

  @js.native
  trait ButtonProps extends js.Object {
    var label: js.UndefOr[String] = js.native
  }

  object ButtonProps {
    def apply(label: String): ButtonProps = {
      val p = js.Dynamic.literal().asInstanceOf[ButtonProps]
      p.label = label
      p
    }
  }

  @js.native
  @JSImport("drt-react", "Button")
  object Button extends js.Function

  val componentButton = JsFnComponent[ButtonProps, Children.None](Button)

}

