package uk.gov.homeoffice.drt.service.staffing

import drt.shared.Shift
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.time.LocalDate
import java.sql.{Date, Timestamp}
import java.time.{LocalDate => JavaLocalDate}

object ShiftUtil {

  def toStaffShiftRow(shift: Shift, createdAt: Timestamp): StaffShiftRow = {
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
    val javaLocalDate = JavaLocalDate.of(localDate.year, localDate.month, localDate.day)
    Date.valueOf(javaLocalDate)
  }

  private def convertToLocalDate(sqlDate: java.sql.Date): LocalDate = {
    val localDate = sqlDate.toLocalDate
    LocalDate(localDate.getYear, localDate.getMonthValue, localDate.getDayOfMonth)
  }

  def currentLocalDate: LocalDate = {
    val now = JavaLocalDate.now()
    LocalDate(now.getYear, now.getMonthValue, now.getDayOfMonth)
  }

  def localDateFromString(date: String): LocalDate = {
    val parts = date.split("-")
    if (parts.length != 3) throw new IllegalArgumentException(s"Invalid date format: $date")
    LocalDate(parts(0).toInt, parts(1).toInt, parts(2).toInt)
  }

  def localDateFromString(date: Option[String]): LocalDate = {
    date.map(localDateFromString).getOrElse(currentLocalDate)
  }

}
