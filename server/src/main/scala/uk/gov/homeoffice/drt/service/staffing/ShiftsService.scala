package uk.gov.homeoffice.drt.service.staffing

import drt.shared.Shift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.time.LocalDate

import java.sql.{Date, Timestamp}
import java.time.{LocalDate => JavaLocalDate}
import scala.concurrent.{ExecutionContext, Future}

trait ShiftsService {
  def getShift(port: String, terminal: String, shiftName: String): Future[Option[Shift]]

  def getShifts(port: String, terminal: String): Future[Seq[Shift]]

  def saveShift(shifts: Seq[Shift]): Future[Int]

  def deleteShift(port: String, terminal: String, shiftName: String): Future[Int]
}

case class ShiftsServiceImpl(staffShiftsDao: StaffShiftsDao)(implicit ec: ExecutionContext) extends ShiftsService {

  private def convertToSqlDate(localDate: LocalDate): java.sql.Date = {
    val javaLocalDate = JavaLocalDate.of(localDate.year, localDate.month, localDate.day)
    Date.valueOf(javaLocalDate)
  }

  private def convertToLocalDate(sqlDate: java.sql.Date): LocalDate = {
    val localDate = sqlDate.toLocalDate
    LocalDate(localDate.getYear, localDate.getMonthValue, localDate.getDayOfMonth)
  }

  private def toStaffShiftRow(shift: Shift, createdBy: Option[String], frequency: Option[String], createdAt: Timestamp): StaffShiftRow = {
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

  private def fromStaffShiftRow(row: StaffShiftRow): Shift = {
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

  override def saveShift(shifts: Seq[Shift]): Future[Int] = {
    val shiftRows = shifts.map(shift => toStaffShiftRow(shift, None, None, new Timestamp(System.currentTimeMillis())))
    Future.sequence(shiftRows.map(staffShiftsDao.insertOrUpdate)).map(_.sum)
  }

  override def deleteShift(port: String, terminal: String, shiftName: String): Future[Int] = staffShiftsDao.deleteStaffShift(port, terminal, shiftName)

  override def getShift(port: String, terminal: String, shiftName: String): Future[Option[Shift]] =
    staffShiftsDao.getStaffShiftByPortAndTerminalAndShiftName(port, terminal, shiftName).map(_.headOption).map(_.map(fromStaffShiftRow))

  override def getShifts(port: String, terminal: String): Future[Seq[Shift]] =
    staffShiftsDao.getStaffShiftsByPortAndTerminal(port, terminal).map(_.map(fromStaffShiftRow))
}