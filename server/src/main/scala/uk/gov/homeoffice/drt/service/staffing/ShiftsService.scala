package uk.gov.homeoffice.drt.service.staffing

import drt.shared.Shift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.db.tables.StaffShiftRow
import uk.gov.homeoffice.drt.service.staffing.ShiftUtil.{fromStaffShiftRow, toStaffShiftRow}

import java.sql.Timestamp
import java.time.{LocalDate => JavaLocalDate}
import scala.concurrent.{ExecutionContext, Future}

trait ShiftsService {
  def getShift(port: String, terminal: String, shiftName: String, startDate: java.sql.Date): Future[Option[Shift]]

  def getShifts(port: String, terminal: String): Future[Seq[Shift]]

  def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]]

  def saveShift(shifts: Seq[Shift]): Future[Int]

  def updateShift(previousShift: Shift, shift: Shift): Future[Int]

  def deleteShift(port: String, terminal: String, shiftName: String): Future[Int]

  def getOverlappingStaffShifts(port: String, terminal: String, shift: StaffShiftRow): Future[Seq[StaffShiftRow]]

  def deleteShifts(): Future[Int]
}

case class ShiftsServiceImpl(staffShiftsDao: StaffShiftsDao)(implicit ec: ExecutionContext) extends ShiftsService {

  override def saveShift(shifts: Seq[Shift]): Future[Int] = {
    val shiftRows = shifts.map(shift => toStaffShiftRow(shift, new Timestamp(System.currentTimeMillis())))
    Future.sequence(shiftRows.map(staffShiftsDao.insertOrUpdate)).map(_.sum)
  }

  override def deleteShift(port: String, terminal: String, shiftName: String): Future[Int] = staffShiftsDao.deleteStaffShift(port, terminal, shiftName)

  override def deleteShifts(): Future[Int] = staffShiftsDao.deleteStaffShifts()

  override def getShift(port: String, terminal: String, shiftName: String, startDate: java.sql.Date): Future[Option[Shift]] =
    staffShiftsDao.getStaffShiftByPortAndTerminal(port, terminal, shiftName, startDate).map(_.map(fromStaffShiftRow))

  override def getShifts(port: String, terminal: String): Future[Seq[Shift]] =
    staffShiftsDao.getStaffShiftsByPortAndTerminal(port, terminal).map(_.map(fromStaffShiftRow))

  override def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]] = getShifts(port, terminal).map { shifts =>
    val localDate: JavaLocalDate = localDateFromString(date)
    shifts.filter { shift =>
      shift.endDate match {
        case None => true
        case Some(endDate) =>
          val javaEndDate = java.time.LocalDate.of(endDate.year, endDate.month, endDate.day)
          javaEndDate.isAfter(localDate)
      }
    }.filter { shift =>
      shift.startDate match {
        case uk.gov.homeoffice.drt.time.LocalDate(year, month, day) =>
          val javaStartDate = java.time.LocalDate.of(year, month, day)
          !javaStartDate.isAfter(localDate)
        case _ => false
      }
    }
  }

  private def localDateFromString(date: Option[String]) = {
    val today = java.time.LocalDate.now()
    val localDate = date.map { d =>
      val parts = d.split("-")
      java.time.LocalDate.of(parts(0).toInt, parts(1).toInt, parts(2).toInt)
    }.getOrElse(today)
    localDate
  }

  override def updateShift(previousShift: Shift, shift: Shift): Future[Int] = staffShiftsDao.updateStaffShift(
    toStaffShiftRow(previousShift, new Timestamp(System.currentTimeMillis())),
    toStaffShiftRow(shift, new Timestamp(System.currentTimeMillis())))

  override def getOverlappingStaffShifts(port: String, terminal: String, shift: StaffShiftRow): Future[Seq[StaffShiftRow]] =
    staffShiftsDao.getOverlappingStaffShifts(port, terminal, shift)
}