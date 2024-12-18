package uk.gov.homeoffice.drt.service.staffing

import drt.shared.StaffShift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.time.LocalDate

import java.sql.{Date, Timestamp}
import java.time.{LocalDate => JavaLocalDate}
import scala.concurrent.{ExecutionContext, Future}

trait StaffShiftsService {
  def getShift(port: String, terminal: String, shiftName: String): Future[Option[StaffShift]]

  def getShifts(port: String, terminal: String): Future[Seq[StaffShift]]

  def saveShift(shifts: Seq[StaffShift]): Future[Int]

  def deleteShift(port: String, terminal: String, shiftName: String): Future[Int]
}

case class StaffShiftsServiceImpl(staffShiftsDao: StaffShiftsDao)(implicit ec: ExecutionContext) extends StaffShiftsService {

  private def convertToSqlDate(localDate: LocalDate): java.sql.Date = {
    val javaLocalDate = JavaLocalDate.of(localDate.year, localDate.month, localDate.day)
    Date.valueOf(javaLocalDate)
  }

  private def convertToLocalDate(sqlDate: java.sql.Date): LocalDate = {
    val localDate = sqlDate.toLocalDate
    LocalDate(localDate.getYear, localDate.getMonthValue, localDate.getDayOfMonth)
  }

  private def toStaffShiftRow(shift: StaffShift, createdBy: Option[String], frequency: Option[String], createdAt: Timestamp): StaffShiftRow = {
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

  private def fromStaffShiftRow(row: StaffShiftRow): StaffShift = {
    StaffShift(
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

  override def saveShift(shifts: Seq[StaffShift]): Future[Int] = {
    val shiftRows = shifts.map(shift => toStaffShiftRow(shift, None, None, new Timestamp(System.currentTimeMillis())))
    Future.sequence(shiftRows.map(staffShiftsDao.insertOrUpdate)).map(_.sum)
  }

  override def deleteShift(port: String, terminal: String, shiftName: String): Future[Int] = staffShiftsDao.deleteStaffShift(port, terminal, shiftName)

  override def getShift(port: String, terminal: String, shiftName: String): Future[Option[StaffShift]] =
    staffShiftsDao.getStaffShiftByPortAndTerminalAndShiftName(port, terminal, shiftName).map(_.headOption).map(_.map(fromStaffShiftRow))

  override def getShifts(port: String, terminal: String): Future[Seq[StaffShift]] =
    staffShiftsDao.getStaffShiftsByPortAndTerminal(port, terminal).map(_.map(fromStaffShiftRow))
}