package drt.client.components

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait StatusTagProps extends js.Object {
  var `type`: String = js.native
  var text: String = js.native
}

object StatusTagProps {
  def apply(`type`: String, text: String): StatusTagProps = {
    val p = (new js.Object).asInstanceOf[StatusTagProps]
    p.`type` = `type`
    p.text = text
    p
  }
}

object StatusTag {
  @js.native
  @JSImport("@drt/drt-react", "StatusTag")
  object RawComponent extends js.Object

  val component = JsFnComponent[StatusTagProps, Children.None](RawComponent)

  def apply(`type`: String, text: String): VdomElement = {
    val props = StatusTagProps(`type` = `type`, text = text)
    component(props)
  }
}
