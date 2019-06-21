package drt.client.components

import japgolly.scalajs.react.{Children, JsComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

object ReactMarkdown {
  @JSImport("react-markdown", JSImport.Default)
  @js.native
  object RawComponent extends js.Object

  @js.native
  trait Props extends js.Object {
    var source: String = js.native
  }

  def props(source: String): Props = {
    val props = (new js.Object).asInstanceOf[Props]
    props.source = source
    props
  }

  val component = JsComponent[Props, Children.None, Null](RawComponent)
}
