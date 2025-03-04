package drt.client.components

import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IPaxDatasource extends js.Object {
  var status: String = js.native
}

object IPaxDatasource {
  def apply(status: String): IPaxDatasource = {
    val p = (new js.Object).asInstanceOf[IPaxDatasource]
    p.status = status
    p
  }
}

object PaxDatasourceComponent {
  @js.native
  @JSImport("@drt/drt-react", "PaxDatasource")
  object RawComponent extends js.Object

  val component = JsFnComponent[IPaxDatasource, Children.None](RawComponent)

  def apply(props: IPaxDatasource): VdomElement = {
    component(props)
  }

}
