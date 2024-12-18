package actors.persistent.staffing

import drt.shared.{StaffAssignment, StaffShift}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalTime, ZoneId}

object StaffingUtil {

  def generateDailyAssignments(shift: StaffShift): Seq[StaffAssignment] = {
    val formatter = DateTimeFormatter.ofPattern("HH:mm")
    val startTime = LocalTime.parse(shift.startTime, formatter)
    val endTime = LocalTime.parse(shift.endTime, formatter)
    val startDate = LocalDate.of(shift.startDate.year, shift.startDate.month, shift.startDate.day)
    val endDate = shift.endDate.map(ed => LocalDate.of(ed.year, ed.month, ed.day)).getOrElse(startDate.plusMonths(6))
    val daysBetween = ChronoUnit.DAYS.between(startDate, endDate).toInt

    (0 to daysBetween).map { day =>
      val currentDate = startDate.plusDays(day)
      val startMillis = currentDate.atTime(startTime).atZone(ZoneId.systemDefault()).toInstant.toEpochMilli
      val endMillis = currentDate.atTime(endTime).atZone(ZoneId.systemDefault()).toInstant.toEpochMilli

      StaffAssignment(
        name = shift.shiftName,
        terminal = Terminal(shift.terminal),
        start = startMillis,
        end = endMillis,
        numberOfStaff = shift.staffNumber,
        createdBy = shift.createdBy
      )
    }
  }


}
