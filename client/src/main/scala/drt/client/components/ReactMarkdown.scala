package drt.client.components

import japgolly.scalajs.react.component.Js.{RawMounted, UnmountedWithRawType}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Children, JsComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
@JSImport("react-markdown", JSImport.Default)
object ReactMarkdown extends js.Object

object Markdown {
  val component = JsComponent[Null, Children.Varargs, Null](ReactMarkdown)

  def apply(source: String): UnmountedWithRawType[Null, Null, RawMounted[Null, Null]] = {
    component(source)
  }
}
