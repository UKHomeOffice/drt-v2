package drt.client.components

import drt.shared.StaffAssignment
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.{Date, UndefOr}

@js.native
sealed trait IUpdateStaffForTimeRangeData extends js.Object {
  var startDayAt: moment.Date
  var startTimeAt: moment.Date
  var endTimeAt: moment.Date
  var endDayAt: moment.Date
  var actualStaff: String
}

object IUpdateStaffForTimeRangeData {
  def apply(startDayAt: moment.Date, startTimeAt: moment.Date, endTimeAt: moment.Date, endDayAt: moment.Date, actualStaff: String): IUpdateStaffForTimeRangeData = {
    val p = (new js.Object).asInstanceOf[IUpdateStaffForTimeRangeData]
    p.startDayAt = startDayAt
    p.startTimeAt = startTimeAt
    p.endTimeAt = endTimeAt
    p.endDayAt = endDayAt
    p.actualStaff = actualStaff
    p
  }
}

@js.native
trait IUpdateStaffForTimeRangeForm extends js.Object {
  var ustd: IUpdateStaffForTimeRangeData
  var interval: Int
  var handleSubmit: js.Function1[IUpdateStaffForTimeRangeData, Unit]
  var cancelHandler: js.Function0[Unit]
}

object IUpdateStaffForTimeRangeForm {
  def apply(ustd: IUpdateStaffForTimeRangeData, interval: Int, handleSubmit: js.Function1[IUpdateStaffForTimeRangeData, Unit], cancelHandler: js.Function0[Unit]): IUpdateStaffForTimeRangeForm = {
    val p = (new js.Object).asInstanceOf[IUpdateStaffForTimeRangeForm]
    p.ustd = ustd
    p.handleSubmit = handleSubmit
    p.interval = interval
    p.cancelHandler = cancelHandler
    p
  }
}

object UpdateStaffForTimeRangeForm {
  @js.native
  @JSImport("@drt/drt-react", "UpdateStaffForTimeRangeForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[IUpdateStaffForTimeRangeForm, Children.None](RawComponent)

  def apply(props: IUpdateStaffForTimeRangeForm): VdomElement = {
    component(props)
  }
}
