package drt.client.components

import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IStaffSuccess extends js.Object {
  var minStaffNumber: Int = js.native
  var message: String = js.native
  var closeHandler: js.Function0[Unit] = js.native
}

object IStaffSuccess {
  def apply(staffNumber: Int, message: String, closeHandler: js.Function0[Unit]): IStaffSuccess = {
    val p = (new js.Object).asInstanceOf[IStaffSuccess]
    p.minStaffNumber = staffNumber
    p.message = message
    p.closeHandler = closeHandler
    p
  }
}

object StaffUpdateSuccess {
  @js.native
  @JSImport("@drt/drt-react", "StaffSuccess")
  object RawComponent extends js.Object

  val component = JsFnComponent[IStaffSuccess, Children.None](RawComponent)

  def apply(props: IStaffSuccess): VdomElement = {
    component(props)
  }

}
