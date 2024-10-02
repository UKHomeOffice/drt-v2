package drt.client.components

import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}

import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport

@js.native
trait IMinStaffForm extends js.Object {
  var port: String = js.native
  var terminal: String = js.native
  var message: String = js.native
  var minStaffNumber: Int = js.native
  var handleSubmit: js.Function1[Int, Unit] = js.native
  var cancelHandler: js.Function0[Unit] = js.native
}

object IMinStaffForm {
  def apply(port: String, terminal: String, message: String, minStaffNumber: Int, handleSubmit: js.Function1[Int, Unit], cancelHandler: js.Function0[Unit]): IMinStaffForm = {
    val p = (new js.Object).asInstanceOf[IMinStaffForm]
    p.port = port
    p.terminal = terminal
    p.message = message
    p.minStaffNumber = minStaffNumber
    p.handleSubmit = handleSubmit
    p.cancelHandler = cancelHandler
    p
  }
}

object MinStaffForm {
  @js.native
  @JSImport("@drt/drt-react", "MinStaffForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[IMinStaffForm, Children.None](RawComponent)

  def apply(props: IMinStaffForm): VdomElement = {
    component(props)
  }
}

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

object StaffSuccess {
  @js.native
  @JSImport("@drt/drt-react", "StaffSuccess")
  object RawComponent extends js.Object

  val component = JsFnComponent[IStaffSuccess, Children.None](RawComponent)

  def apply(props: IStaffSuccess): VdomElement = {
    component(props)
  }

}
