package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.util.ShiftUtil.{convertToSqlDate, fromStaffShiftRow, localDateFromString, toStaffShiftRow}
import scala.concurrent.{ExecutionContext, Future}

trait ShiftsService {
  def getShift(port: String, terminal: String, shiftName: String, startDate: LocalDate): Future[Option[Shift]]

  def getShifts(port: String, terminal: String): Future[Seq[Shift]]

  def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]]

  def saveShift(shifts: Seq[Shift]): Future[Int]

  def updateShift(previousShift: Shift, shift: Shift): Future[Int]

  def deleteShift(port: String, terminal: String, shiftName: String): Future[Int]

  def getOverlappingStaffShifts(port: String, terminal: String, shift: Shift): Future[Seq[Shift]]

  def deleteShifts(): Future[Int]
}

case class ShiftsServiceImpl(staffShiftsDao: StaffShiftsDao)(implicit ec: ExecutionContext) extends ShiftsService {

  override def saveShift(shifts: Seq[Shift]): Future[Int] = {
    Future.sequence(shifts.map(staffShiftsDao.insertOrUpdate)).map(_.sum)
  }

  override def deleteShift(port: String, terminal: String, shiftName: String): Future[Int] = staffShiftsDao.deleteStaffShift(port, terminal, shiftName)

  override def deleteShifts(): Future[Int] = staffShiftsDao.deleteStaffShifts()

  override def getShift(port: String, terminal: String, shiftName: String, startDate: LocalDate): Future[Option[Shift]] =
    staffShiftsDao.getStaffShift(port, terminal, shiftName, startDate)

  override def getShifts(port: String, terminal: String): Future[Seq[Shift]] =
    staffShiftsDao.getStaffShiftsByPortAndTerminal(port, terminal)

  override def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]] = getShifts(port, terminal).map { shifts =>
    val localDate: LocalDate = localDateFromString(date)
    shifts.filter { shift =>
      shift.startDate.compare(localDate) <= 0 &&
        (shift.endDate.isEmpty || shift.endDate.exists(_.compare(localDate) >= 0))
    }
  }

  override def updateShift(previousShift: Shift, shift: Shift): Future[Int] = staffShiftsDao.updateStaffShift(previousShift, shift)

  override def getOverlappingStaffShifts(port: String, terminal: String, shift: Shift): Future[Seq[Shift]] =
    staffShiftsDao.getOverlappingStaffShifts(port, terminal, shift)
}