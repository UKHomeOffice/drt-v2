package drt.client.components

import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IStaffUpdateSuccess extends js.Object {
  var minStaffNumber: Int = js.native
  var message: String = js.native
  var closeHandler: js.Function0[Unit] = js.native
}

object IStaffUpdateSuccess {
  def apply(staffNumber: Int, message: String, closeHandler: js.Function0[Unit]): IStaffUpdateSuccess = {
    val p = (new js.Object).asInstanceOf[IStaffUpdateSuccess]
    p.minStaffNumber = staffNumber
    p.message = message
    p.closeHandler = closeHandler
    p
  }
}

object StaffUpdateSuccess {
  @js.native
  @JSImport("@drt/drt-react", "StaffUpdateSuccess")
  object RawComponent extends js.Object

  val component = JsFnComponent[IStaffUpdateSuccess, Children.None](RawComponent)

  def apply(props: IStaffUpdateSuccess): VdomElement = {
    component(props)
  }

}
