package uk.gov.homeoffice.drt.service.staffing

import drt.shared.StaffShift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow

import java.sql.Timestamp
import scala.concurrent.{ExecutionContext, Future}

trait StaffShiftsService {
  def getShift(port: String, terminal: String, shiftName: String): Future[Option[StaffShift]]

  def getShifts(port: String, terminal: String): Future[Seq[StaffShift]]

  def saveShift(shifts: Seq[StaffShift]): Future[Int]

  def deleteShift(port: String, terminal: String, shiftName: String): Future[Int]
}

case class StaffShiftsServiceImpl(staffShiftsDao: StaffShiftsDao)(implicit ec: ExecutionContext) extends StaffShiftsService {
  private def toStaffShiftRow(shift: StaffShift, createdBy: Option[String], frequency: Option[String], createdAt: Timestamp): StaffShiftRow = {
    StaffShiftRow(
      port = shift.port,
      terminal = shift.terminal,
      shiftName = shift.shiftName,
      startTime = shift.startTime,
      endTime = shift.endTime,
      staffNumber = shift.staffNumber,
      createdBy = createdBy,
      frequency = frequency,
      createdAt = createdAt
    )
  }

  private def fromStaffShiftRow(row: StaffShiftRow): StaffShift = {
    StaffShift(
      port = row.port,
      terminal = row.terminal,
      shiftName = row.shiftName,
      startTime = row.startTime,
      endTime = row.endTime,
      staffNumber = row.staffNumber
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