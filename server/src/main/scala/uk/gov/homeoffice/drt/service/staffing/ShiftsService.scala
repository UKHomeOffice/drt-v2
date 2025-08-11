package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.util.ShiftUtil.{fromStaffShiftRow, localDateFromString, toStaffShiftRow}
import scala.concurrent.{ExecutionContext, Future}

trait ShiftsService {
  def getShift(port: String, terminal: String, shiftName: String, startDate: LocalDate): Future[Option[Shift]]

  def getShifts(port: String, terminal: String): Future[Seq[Shift]]

  def latestStaffShiftForADate(port: String, terminal: String, startDate: LocalDate, startTime: String)(implicit ec: ExecutionContext): Future[Option[Shift]]

  def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]]

  def saveShift(shifts: Seq[Shift]): Future[Int]

  def updateShift(previousShift: Shift, shift: Shift): Future[Shift]

  def createNewShiftWhileEditing(previousShift: Shift, shiftRow: Shift)(implicit ec: ExecutionContext): Future[Shift]

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
    staffShiftsDao.searchStaffShift(port, terminal, shiftName, startDate)

  override def getShifts(port: String, terminal: String): Future[Seq[Shift]] =
    staffShiftsDao.getStaffShiftsByPortAndTerminal(port, terminal)

  override def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]] = getShifts(port, terminal).map { shifts =>
    val localDate: LocalDate = localDateFromString(date)
    shifts.filter { shift =>
      shift.startDate.compare(localDate) <= 0 &&
        (shift.endDate.isEmpty || shift.endDate.exists(_.compare(localDate) >= 0))
    }
  }

  override def updateShift(previousShift: Shift, shift: Shift): Future[Shift] = staffShiftsDao.updateStaffShift(previousShift, shift)

  override def getOverlappingStaffShifts(port: String, terminal: String, shift: Shift): Future[Seq[Shift]] =
    staffShiftsDao.getOverlappingStaffShifts(port, terminal, shift)

  override def createNewShiftWhileEditing(previousShift: Shift, shiftRow: Shift)(implicit ec: ExecutionContext): Future[Shift] = {
    staffShiftsDao.isShiftAfterStartDateExists(shiftRow).flatMap {
      case true => Future.failed(
        new IllegalArgumentException(s"Future Shift for ${shiftRow.port} ${shiftRow.terminal} ${shiftRow.shiftName} on ${shiftRow.startDate} already exists"))
      case false => staffShiftsDao.createNewShiftWhileEditing(previousShift, shiftRow).map { newShift =>
        fromStaffShiftRow(toStaffShiftRow(newShift.copy(createdAt = System.currentTimeMillis())))
      }
    }
  }

  override def latestStaffShiftForADate(port: String,
                                        terminal: String,
                                        startDate: LocalDate,
                                        startTime: String)
                                       (implicit ec: ExecutionContext): Future[Option[Shift]] =
    staffShiftsDao.latestStaffShiftForADate(port, terminal, startDate, startTime)
}