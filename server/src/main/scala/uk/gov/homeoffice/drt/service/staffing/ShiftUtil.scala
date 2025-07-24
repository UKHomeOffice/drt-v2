package uk.gov.homeoffice.drt.service.staffing

import drt.shared.Shift
import org.joda.time.{DateTimeZone, LocalDateTime}
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

import java.sql.{Date, Timestamp}
import java.time.{LocalDate => JavaLocalDate}

object ShiftUtil {

  def toStaffShiftRow(shift: Shift, createdBy: Option[String], frequency: Option[String], createdAt: Timestamp): StaffShiftRow = {
    StaffShiftRow(
      port = shift.port,
      terminal = shift.terminal,
      shiftName = shift.shiftName,
      startDate = convertToSqlDate(shift.startDate),
      startTime = shift.startTime,
      endTime = shift.endTime,
      endDate = shift.endDate.map(convertToSqlDate),
      staffNumber = shift.staffNumber,
      frequency = shift.frequency,
      createdBy = shift.createdBy,
      createdAt = createdAt
    )
  }

  def fromStaffShiftRow(row: StaffShiftRow): Shift = {
    Shift(
      port = row.port,
      terminal = row.terminal,
      shiftName = row.shiftName,
      startDate = convertToLocalDate(row.startDate),
      startTime = row.startTime,
      endTime = row.endTime,
      endDate = row.endDate.map(convertToLocalDate),
      staffNumber = row.staffNumber,
      frequency = row.frequency,
      createdBy = row.createdBy,
      createdAt = row.createdAt.getTime
    )
  }

  def convertToSqlDate(localDate: LocalDate): java.sql.Date = {
    val javaLocalDate = JavaLocalDate.of(localDate.year, localDate.month , localDate.day)
    Date.valueOf(javaLocalDate)
  }

  def convertToLocalDate(sqlDate: java.sql.Date): LocalDate = {
    val localDate = sqlDate.toLocalDate
    LocalDate(localDate.getYear, localDate.getMonthValue, localDate.getDayOfMonth)
  }

  def dateFromString(date: String): Date = {
    java.sql.Date.valueOf(date)
  }

  def safeSDate(year: Int, month: Int, day: Int, hour: Int, minute: Int, tz: DateTimeZone): SDateLike = {
    val ldt = new LocalDateTime(year, month, day, hour, minute)
    val dt = if (!tz.isLocalDateTimeGap(ldt)) ldt.toDateTime(tz)
    else ldt.plusHours(1).toDateTime(tz) // shift forward if in gap
    SDate(dt)
  }

}
