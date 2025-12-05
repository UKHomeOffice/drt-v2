package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.ShiftStaffRolling
import uk.gov.homeoffice.drt.db.dao.ShiftStaffRollingDao

import scala.concurrent.{ExecutionContext, Future}

trait IShiftStaffRollingService {
  def upsertShiftStaffRolling(shiftStaffRolling: ShiftStaffRolling): Future[Int]

  def latestShiftStaffRolling(port: String, terminal: String): Future[Option[ShiftStaffRolling]]

}

case class ShiftStaffRollingService(shiftStaffRollingDao: ShiftStaffRollingDao)(implicit ex: ExecutionContext) extends IShiftStaffRollingService {

  override def upsertShiftStaffRolling(shiftStaffRolling: ShiftStaffRolling): Future[Int] =
    shiftStaffRollingDao.upsertShiftStaffRolling(shiftStaffRolling)

  override def latestShiftStaffRolling(port: String, terminal: String): Future[Option[ShiftStaffRolling]] =
    shiftStaffRollingDao.latestShiftStaffRolling(port, terminal)
}
