package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.ShiftMeta
import uk.gov.homeoffice.drt.db.dao.ShiftMetaInfoDao

import scala.concurrent.{ExecutionContext, Future}

trait ShiftMetaInfoService {
  def insertShiftMetaInfo(shiftMeta: ShiftMeta): Future[Int]

  def getShiftMetaInfo(port: String, terminal: String): Future[Option[ShiftMeta]]

  def updateShiftAssignmentsMigratedAt(port: String,
                                       terminal: String,
                                       shiftAssignmentsMigratedAt: Option[java.sql.Timestamp]): Future[Option[ShiftMeta]]

}

case class ShiftMetaInfoServiceImpl(shiftMetaInfoDao: ShiftMetaInfoDao)(implicit ex: ExecutionContext) extends ShiftMetaInfoService {
  override def insertShiftMetaInfo(shiftMeta: ShiftMeta): Future[Int] =
    shiftMetaInfoDao.insertShiftMetaInfo(shiftMeta)

  override def getShiftMetaInfo(port: String, terminal: String): Future[Option[ShiftMeta]] =
    shiftMetaInfoDao.getShiftMetaInfo(port, terminal)
      .map(_.map(shiftMetaRow => ShiftMeta(shiftMetaRow.port, shiftMetaRow.terminal, shiftMetaRow.shiftAssignmentsMigratedAt)))

  override def updateShiftAssignmentsMigratedAt(port: String,
                                                terminal: String,
                                                shiftAssignmentsMigratedAt: Option[java.sql.Timestamp]): Future[Option[ShiftMeta]] =
    shiftMetaInfoDao.updateShiftAssignmentsMigratedAt(port, terminal, shiftAssignmentsMigratedAt)
      .map(_.map(shiftMetaRow => ShiftMeta(shiftMetaRow.port, shiftMetaRow.terminal, shiftMetaRow.shiftAssignmentsMigratedAt)))

}