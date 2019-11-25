package drt.staff

import drt.shared.Terminals.Terminal
import drt.shared.{MilliDate, ShiftAssignments, StaffAssignment, Terminals}
import org.joda.time.DateTime
import play.api.libs.json.{JsError, JsObject, JsResult, JsString, JsSuccess, JsValue, Json, OFormat, Reads, Writes}
import services.SDate.implicits._
import services.graphstages.Crunch.europeLondonTimeZone

case class StaffShift(port_code: String, terminal: Terminal, staff: String, shift_start: String)

case class StaffShifts(shifts: List[StaffShift])

object ImportStaff {
  def staffJsonToShifts(staffJson: JsValue): Option[ShiftAssignments] = {
    implicit val terminalReads: Reads[Terminal] = new Reads[Terminal] {
      override def reads(json: JsValue): JsResult[Terminal] = json match {
        case j: JsString => JsSuccess(Terminals.Terminal(j.value))
        case u => JsError(s"invalid terminal json value: $u")
      }
    }
    implicit val terminalWrites: Writes[Terminal] = new Writes[Terminal] {
      override def writes(o: Terminal): JsValue = JsString(o.toString)//Json.obj("name" -> o.toString)
    }
    implicit val shiftFormat: OFormat[StaffShift] = Json.format[StaffShift]
    implicit val shiftsFormat: OFormat[StaffShifts] = Json.format[StaffShifts]

    val maybeAssignments = staffJson.validate[StaffShifts].asOpt map {
      case StaffShifts(shifts) =>
        shifts.zipWithIndex.map {
          case (shift, index) =>
            //The client deals in local time, and these shifts are sent to the client as strings with no timezone for now.
            //TODO: store shifts not as strings.
            val shiftStartDate = new DateTime(shift.shift_start).withZone(europeLondonTimeZone)
            val shiftsEndDate = shiftStartDate.addMinutes(14)

            StaffAssignment(index.toString, shift.terminal, MilliDate(shiftStartDate.millisSinceEpoch), MilliDate(shiftsEndDate.millisSinceEpoch), shift.staff.toInt, Option("API"))
        }
    }
    maybeAssignments.map(ShiftAssignments(_))
  }

}
