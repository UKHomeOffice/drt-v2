package drt.client.components.styles

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait ILocalDateProvider extends js.Object {
  var children: js.Object = js.native
}

object ILocalDateProvider {
  def apply(children: VdomNode*): ILocalDateProvider = {
    val p = (new js.Object).asInstanceOf[ILocalDateProvider]
    p.children = children.toVdomArray.rawNode.asInstanceOf[js.Object]
    p
  }
}

object LocalDateProvider {
  @js.native
  @JSImport("@drt/drt-react", "LocalDateProvider")
  object RawComponent extends js.Object

  val component = JsFnComponent[ILocalDateProvider, Children.None](RawComponent)

  def apply(props: ILocalDateProvider): VdomElement = {
    component(props)
  }

}