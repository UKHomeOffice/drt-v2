package uk.gov.homeoffice.drt.service.staffing

import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.db.dao.StaffShiftsDao
import uk.gov.homeoffice.drt.time.{LocalDate, SDate}
import uk.gov.homeoffice.drt.util.ShiftUtil.{fromStaffShiftRow, localDateFromString, toStaffShiftRow}

import scala.concurrent.{ExecutionContext, Future}

trait ShiftsService {
  implicit val ec: ExecutionContext

  def getShift(port: String, terminal: String, shiftName: String, startDate: LocalDate, startTime: String): Future[Option[Shift]]

  def getShifts(port: String, terminal: String): Future[Seq[Shift]]

  def latestStaffShiftForADate(port: String, terminal: String, startDate: LocalDate, startTime: String)(implicit ec: ExecutionContext): Future[Option[Shift]]

  def getActiveShifts(port: String, terminal: String, date: Option[String]): Future[Seq[Shift]] = getShifts(port, terminal).map { shifts =>
    val localDate: LocalDate = localDateFromString(date)
    shifts.filter { shift =>
      shift.startDate.compare(localDate) <= 0 &&
        (shift.endDate.isEmpty || shift.endDate.exists(_.compare(localDate) >= 0))
    }
  }

  def getActiveShiftsForViewRange(port: String, terminal: String, dayRange: Option[String], date: Option[String]): Future[Seq[Shift]]

  def saveShift(shifts: Seq[Shift]): Future[Int]

  def updateShift(previousShift: Shift, shift: Shift): Future[Shift]

  def createNewShiftWhileEditing(previousShift: Shift, shiftRow: Shift): Future[(Shift, Option[Shift])]

  def deleteShift(shift: Shift): Future[Shift]

  def getOverlappingStaffShifts(port: String, terminal: String, shift: Shift): Future[Seq[Shift]]

  def deleteShifts(): Future[Int]

}

case class ShiftsServiceImpl(staffShiftsDao: StaffShiftsDao)(implicit val ec: ExecutionContext) extends ShiftsService {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def saveShift(shifts: Seq[Shift]): Future[Int] = {
    Future.sequence(shifts.map(staffShiftsDao.insertOrUpdate)).map(_.sum)
  }

  override def deleteShift(shift: Shift): Future[Shift] = staffShiftsDao.deleteStaffShift(shift.port,
    shift.terminal,
    shift.shiftName,
    shift.startDate,
    shift.startTime).map(_ => shift)

  override def deleteShifts(): Future[Int] = staffShiftsDao.deleteStaffShifts()

  override def getShifts(port: String, terminal: String): Future[Seq[Shift]] =
    staffShiftsDao.getStaffShiftsByPortAndTerminal(port, terminal)


  override def getActiveShiftsForViewRange(port: String, terminal: String, dayRange: Option[String], date: Option[String]): Future[Seq[Shift]] =
    getShifts(port, terminal).map { shifts =>
      val viewDate: LocalDate = localDateFromString(date)
      dayRange.map(_.toLowerCase) match {
        case Some("weekly") =>
          val viewingSDate = SDate(viewDate.toISOString)
          val startOfWeek = SDate.firstDayOfWeek(viewingSDate)
          val endOfWeek = SDate.lastDayOfWeek(viewingSDate)
          val weekStart = LocalDate(startOfWeek.getFullYear, startOfWeek.getMonth, startOfWeek.getDate)
          val weekEnd = LocalDate(endOfWeek.getFullYear, endOfWeek.getMonth, endOfWeek.getDate)
          shifts.filter(shift => (shift.startDate.compare(weekEnd) <= 0) &&
            (shift.endDate.exists(ed => ed.compare(weekStart) >= 0) || shift.endDate.isEmpty))
        case Some("daily") => shifts.filter(shift => shift.startDate.compare(localDateFromString(date)) <= 0 &&
          (shift.endDate.isEmpty || shift.endDate.exists(_.compare(localDateFromString(date)) >= 0)))
        case _ => shifts.filter(shift => shift.startDate.compare(LocalDate(year = viewDate.year, month = viewDate.month + 1, day = 1)) < 0 &&
          (shift.endDate.isEmpty || shift.endDate.exists(ed => ed.compare(LocalDate(year = viewDate.year, month = viewDate.month, day = 1)) >= 0)))
      }
    }

  override def updateShift(previousShift: Shift, shift: Shift): Future[Shift] = staffShiftsDao.updateStaffShift(previousShift, shift)

  override def getOverlappingStaffShifts(port: String, terminal: String, shift: Shift): Future[Seq[Shift]] =
    staffShiftsDao.getOverlappingStaffShifts(port, terminal, shift)

  override def createNewShiftWhileEditing(previousShift: Shift, shiftRow: Shift): Future[(Shift, Option[Shift])] = {
    staffShiftsDao.latestShiftAfterStartDateExists(shiftRow).flatMap {
      case Some(existingFutureShift) =>
        staffShiftsDao.updateStaffShift(previousShift, existingFutureShift, shiftRow).map { updatedShift =>
          (fromStaffShiftRow(toStaffShiftRow(updatedShift.copy(createdAt = System.currentTimeMillis()))), Some(existingFutureShift))
        }

      case None => staffShiftsDao.createNewShiftWhileEditing(previousShift, shiftRow).map { newShift =>
        (fromStaffShiftRow(toStaffShiftRow(newShift.copy(createdAt = System.currentTimeMillis()))), None)
      }
    }
  }

  override def latestStaffShiftForADate(port: String,
                                        terminal: String,
                                        startDate: LocalDate,
                                        startTime: String)
                                       (implicit ec: ExecutionContext): Future[Option[Shift]] =
    staffShiftsDao.latestStaffShiftForADate(port, terminal, startDate, startTime)

  override def getShift(port: String, terminal: String, shiftName: String, startDate: LocalDate, startTime: String): Future[Option[Shift]] =
    staffShiftsDao.getStaffShift(port, terminal, shiftName, startDate, startTime)
}