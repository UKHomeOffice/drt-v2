package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.ShiftStaffRolling
import uk.gov.homeoffice.drt.db.dao.ShiftStaffRollingDao

import scala.concurrent.{ExecutionContext, Future}

trait IShiftStaffRollingService {
  def upsertShiftStaffRolling(shiftStaffRolling: ShiftStaffRolling): Future[Int]

  def getShiftStaffRolling(port: String, terminal: String): Future[Seq[ShiftStaffRolling]]
}

case class ShiftStaffRollingService(shiftStaffRollingDao : ShiftStaffRollingDao)(implicit ex: ExecutionContext) extends IShiftStaffRollingService {

  override def upsertShiftStaffRolling(shiftStaffRolling: ShiftStaffRolling): Future[Int] =
    shiftStaffRollingDao.upsertShiftStaffRolling(shiftStaffRolling)

  override def getShiftStaffRolling(port: String, terminal: String): Future[Seq[ShiftStaffRolling]] =
    shiftStaffRollingDao.getShiftStaffRolling(port, terminal)
}
