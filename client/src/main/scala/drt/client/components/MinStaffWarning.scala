package drt.client.components

import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IMinStaffWarning extends js.Object {
  var message1: String = js.native
  var message2: String = js.native
  var minStaff: Int = js.native
  var handleClick: js.Function0[Unit] = js.native
}

object IMinStaffWarning {
  def apply(message1: String, message2: String, minStaff: Option[Int], handleClick: js.Function0[Unit]): IMinStaffWarning = {
    val p = (new js.Object).asInstanceOf[IMinStaffWarning]
    p.message1 = message1
    p.message2 = message2
    p.minStaff = minStaff.getOrElse(0)
    p.handleClick = handleClick
    p
  }
}

object MinStaffWarning {

  @js.native
  @JSImport("@drt/drt-react", "MinStaffWarning")
  object RawComponent extends js.Object

  val component = JsFnComponent[IMinStaffWarning, Children.None](RawComponent)

  def apply(props: IMinStaffWarning): VdomElement = {
    component(props)
  }
}
