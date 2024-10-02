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
  var dayAt: moment.Date
  var startTime: moment.Date
  var endTime: js.UndefOr[moment.Date]
  var actualStaff: js.UndefOr[String]
}


object IEditShiftStaff {
  def apply(dayAt: moment.Date, startTime: moment.Date, endTime: js.UndefOr[moment.Date], actualStaff: js.UndefOr[String]): IEditShiftStaff = {
    val p = (new js.Object).asInstanceOf[IEditShiftStaff]
    p.dayAt = dayAt
    p.startTime = startTime
    p.endTime = endTime
    p.actualStaff = actualStaff
    p
  }

  implicit val rw: ReadWriter[EditShiftStaff] = macroRW[EditShiftStaff]

  def toStaffAssignment(obj: IEditShiftStaff, terminal: Terminal): StaffAssignment = {

    val combinedStartTime: Double = Date.UTC(
      obj.dayAt.year(),
      obj.dayAt.month(),
      obj.dayAt.date,
      obj.startTime.toDate().getUTCHours.toInt,
      obj.startTime.toDate().getUTCMinutes.toInt,
      obj.startTime.toDate().getUTCSeconds.toInt)

    val combinedEndTime: UndefOr[Double] = obj.endTime.map(e => Date.UTC(
      obj.dayAt.year(),
      obj.dayAt.month(),
      obj.dayAt.date(),
      e.toDate.getUTCHours.toInt,
      e.toDate.getUTCMinutes.toInt,
      e.toDate.getUTCSeconds.toInt))

    StaffAssignment(obj.dayAt.toISOString,
      terminal,
      combinedStartTime.toLong,
      combinedEndTime.map(a => a.toLong).getOrElse(combinedStartTime.toLong),
      obj.actualStaff.toOption.map(_.toInt).getOrElse(0), None)
  }
}

@js.native
trait IEditShiftStaffForm extends js.Object {
  var ssf: IEditShiftStaff
  var handleSubmit: js.Function1[IEditShiftStaff, Unit]
  var cancelHandler: js.Function0[Unit]
}

object IEditShiftStaffForm {
  def apply(editShiftStaff: IEditShiftStaff, handleSubmit: js.Function1[IEditShiftStaff, Unit], cancelHandler: js.Function0[Unit]): IEditShiftStaffForm = {
    val p = (new js.Object).asInstanceOf[IEditShiftStaffForm]
    p.ssf = editShiftStaff
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
