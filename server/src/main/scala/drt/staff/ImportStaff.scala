package drt.staff

import drt.shared.{ShiftAssignments, StaffAssignment}
import org.joda.time.DateTime
import play.api.libs.json._
import uk.gov.homeoffice.drt.ports.Terminals
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.SDate.implicits._
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone

case class StaffShift(port_code: String, terminal: Terminal, staff: String, shift_start: String)

case class StaffShifts(shifts: List[StaffShift])

object ImportStaff {
  def staffJsonToShifts(staffJson: JsValue): Option[ShiftAssignments] = {
    implicit val terminalReads: Reads[Terminal] = {
      case j: JsString => JsSuccess(Terminals.Terminal(j.value))
      case u => JsError(s"invalid terminal json value: $u")
    }
    implicit val terminalWrites: Writes[Terminal] = (o: Terminal) => JsString(o.toString)
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

            StaffAssignment(index.toString, shift.terminal, shiftStartDate.millisSinceEpoch, shiftsEndDate.millisSinceEpoch, shift.staff.toInt, Option("API"))
        }
    }
    maybeAssignments.map(ShiftAssignments(_))
  }

}
