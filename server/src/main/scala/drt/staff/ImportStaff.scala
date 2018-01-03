package drt.staff

import drt.shared.StaffTimeSlotsForMonth
import org.joda.time.{DateTime, DateTimeZone}
import play.api.libs.json.{JsValue, Json}
import services.SDate
import services.SDate.implicits._

case class StaffShift(port_code: String, terminal: String, staff: String, shift_start: String)

case class StaffShifts(shifts: List[StaffShift])

object ImportStaff {
  def staffJsonToShifts(staffJson: JsValue): Option[String] = {
    implicit val shiftFormat = Json.format[StaffShift]
    implicit val shiftsFormat = Json.format[StaffShifts]
    staffJson.validate[StaffShifts].asOpt map {
      case StaffShifts(shifts) =>
        shifts.zipWithIndex.map{
          case (shift, index) =>
            //The client deals in local time, and these shifts are sent to the client as strings with no timezone for now.
            //TODO: store shifts not as strings.
            val shiftStartDate = new DateTime(shift.shift_start).withZone(DateTimeZone.forID("Europe/London"))
            val shiftsEndDate = shiftStartDate.addMinutes(14)

            f"shift$index, ${shift.terminal}, ${shiftStartDate.ddMMyyString}, ${shiftStartDate.getHours()}%02d:${shiftStartDate.getMinutes()}%02d, ${shiftsEndDate.getHours()}%02d:${shiftsEndDate.getMinutes()}%02d, ${shift.staff}"
        }.mkString("\n")
    }
  }

}
