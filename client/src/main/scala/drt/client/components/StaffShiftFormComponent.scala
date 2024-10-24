package drt.client.components

import drt.client.services.JSDateConversions.SDate
import scala.scalajs.js
import scala.scalajs.js.annotation.JSImport
import japgolly.scalajs.react.{Children, JsFnComponent}
import japgolly.scalajs.react.vdom.VdomElement
import moment.Moment
import upickle.default._


@js.native
sealed trait IShiftStaffForm extends js.Object {
  var port: String
  var terminal: String
  var shiftName: String
  var startAt: Moment
  var periodInMinutes: Double
  var endAt: js.UndefOr[Moment]
  var frequency: js.UndefOr[String]
  var actualStaff: js.UndefOr[Int]
  var minimumRosteredStaff: js.UndefOr[Int]
  var email: String
}

case class ShiftStaffData(port: String,
                          terminal: String,
                          shiftName: String,
                          startAt: Long,
                          periodInMinutes: Double,
                          endAt: Option[Long],
                          frequency: Option[String],
                          actualStaff: Option[Int],
                          minimumRosteredStaff: Option[Int],
                          email: String)


object IShiftStaffForm {
  def apply(port: String, terminal: String, shiftName: String, startAt: Moment, periodInMinutes: Double, endAt: js.UndefOr[Moment], frequency: js.UndefOr[String], actualStaff: js.UndefOr[Int], minimumRosteredStaff: js.UndefOr[Int], email: String): IShiftStaffForm = {
    val p = (new js.Object).asInstanceOf[IShiftStaffForm]
    p.port = port
    p.terminal = terminal
    p.shiftName = shiftName
    p.startAt = startAt
    p.periodInMinutes = periodInMinutes
    p.endAt = endAt
    p.frequency = frequency
    p.actualStaff = actualStaff
    p.minimumRosteredStaff = minimumRosteredStaff
    p.email = email
    p
  }

  implicit val rw: ReadWriter[ShiftStaffData] = macroRW[ShiftStaffData]
  def fromShiftStaffDataJson(json: String): ShiftStaffData = read[ShiftStaffData](json)

  def toShiftStaffDataJson(obj: IShiftStaffForm): String =
    write(ShiftStaffData(port = obj.port,
      terminal = obj.terminal,
      shiftName = obj.shiftName,
      startAt = SDate.JSSDate(obj.startAt.utc).millisSinceEpoch,
      periodInMinutes = obj.periodInMinutes,
      endAt = None,
      frequency = obj.frequency.toOption,
      actualStaff = if (obj.actualStaff.isEmpty) None else obj.actualStaff.map(_.toInt).toOption,
      minimumRosteredStaff =  if(obj.minimumRosteredStaff.isEmpty)  None else obj.minimumRosteredStaff.map(_.toInt).toOption,
      email = obj.email))

}

@js.native
trait ShiftStaffFormData extends js.Object {
  var ssf: IShiftStaffForm
  var handleSubmit: js.Function1[IShiftStaffForm, Unit]
  var cancelHandler: js.Function0[Unit]
}

object ShiftStaffFormData {
  def apply(shiftStaffForm: IShiftStaffForm, handleSubmit: js.Function1[IShiftStaffForm, Unit], cancelHandler: js.Function0[Unit]): ShiftStaffFormData = {
    val p = (new js.Object).asInstanceOf[ShiftStaffFormData]
    p.ssf = shiftStaffForm
    p.handleSubmit = handleSubmit
    p.cancelHandler = cancelHandler
    p
  }
}

object StaffShiftFormComponent {
  @js.native
  @JSImport("@drt/drt-react", "ShiftStaffForm")
  object RawComponent extends js.Object

  val component = JsFnComponent[ShiftStaffFormData, Children.None](RawComponent)

  def apply(props: ShiftStaffFormData): VdomElement = {
    component(props)
  }
}

