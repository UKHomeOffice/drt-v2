package drt.client.components

import drt.shared.StaffAssignment
import japgolly.scalajs.react.vdom.VdomElement
import japgolly.scalajs.react.{Children, JsFnComponent}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import upickle.default._
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import scala.scalajs.js.{Date, UndefOr}

case class EditShiftStaff(port: String,
                          terminal: String,
                          shiftName: String,
                          startAt: Long,
                          periodInMinutes: Int,
                          endAt: Option[Long],
                          frequency: Option[String],
                          actualStaff: Option[Int],
                          minimumRosteredStaff: Option[Int],
                          email: String
                         )

@js.native
sealed trait IEditShiftStaff extends js.Object {
  var startDayAt: moment.Date
  var startTimeAt: moment.Date
  var endTimeAt: moment.Date
  var endDayAt: moment.Date
  var actualStaff: String
}

object IEditShiftStaff {
  def apply(startDayAt: moment.Date, startTimeAt: moment.Date, endTimeAt: moment.Date, endDayAt: moment.Date, actualStaff: String): IEditShiftStaff = {
    val p = (new js.Object).asInstanceOf[IEditShiftStaff]
    p.startDayAt = startDayAt
    p.startTimeAt = startTimeAt
    p.endTimeAt = endTimeAt
    p.endDayAt = endDayAt
    p.actualStaff = actualStaff
    p
  }

  implicit val rw: ReadWriter[EditShiftStaff] = macroRW[EditShiftStaff]

  def toStaffAssignment(obj: IEditShiftStaff, terminal: Terminal): StaffAssignment = {

    val combinedStartTime: Double = Date.UTC(
      obj.startDayAt.year(),
      obj.startDayAt.month(),
      obj.startDayAt.date,
      obj.startTimeAt.utc.toDate().getUTCHours.toInt,
      obj.startTimeAt.utc.toDate().getUTCMinutes.toInt,
      obj.startTimeAt.utc.toDate().getUTCSeconds.toInt)

    val combinedEndTime: UndefOr[Double] = Date.UTC(
      obj.startDayAt.year(),
      obj.startDayAt.month(),
      obj.startDayAt.date(),
      obj.endTimeAt.utc.toDate().getUTCHours.toInt,
      obj.endTimeAt.utc.toDate().getUTCMinutes.toInt,
      obj.endTimeAt.utc.toDate().getUTCSeconds.toInt)

    StaffAssignment(obj.startDayAt.toISOString,
      terminal,
      combinedStartTime.toLong,
      combinedEndTime.map(a => a.toLong).getOrElse(combinedStartTime.toLong),
      obj.actualStaff.toInt,
      None)
  }
}

@js.native
trait IEditShiftStaffForm extends js.Object {
  var essf: IEditShiftStaff
  var handleSubmit: js.Function1[IEditShiftStaff, Unit]
  var cancelHandler: js.Function0[Unit]
}

object IEditShiftStaffForm {
  def apply(editShiftStaff: IEditShiftStaff, handleSubmit: js.Function1[IEditShiftStaff, Unit], cancelHandler: js.Function0[Unit]): IEditShiftStaffForm = {
    val p = (new js.Object).asInstanceOf[IEditShiftStaffForm]
    p.essf = editShiftStaff
    p.handleSubmit = handleSubmit
    p.cancelHandler = cancelHandler
    p
  }
}

object EditShiftStaffForm {
  @js.native
  @JSImport("@drt/drt-react", "EditShiftStaffForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[IEditShiftStaffForm, Children.None](RawComponent)

  def apply(props: IEditShiftStaffForm): VdomElement = {
    component(props)
  }
}