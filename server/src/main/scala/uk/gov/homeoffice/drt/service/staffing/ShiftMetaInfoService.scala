package uk.gov.homeoffice.drt.service.staffing

import uk.gov.homeoffice.drt.ShiftMeta
import uk.gov.homeoffice.drt.db.dao.ShiftMetaInfoDao

import scala.concurrent.{ExecutionContext, Future}

trait ShiftMetaInfoService {
  def insertShiftMetaInfo(port: String,
                          terminal: String,
                          shiftAssignmentsMigratedAt: Option[java.sql.Timestamp],
                          latestShiftAppliedAt: Option[java.sql.Timestamp])(implicit ex: ExecutionContext): Future[Int]

  def getShiftMetaInfo(port: String, terminal: String)(implicit ex: ExecutionContext): Future[Option[ShiftMeta]]

  def updateShiftAssignmentsMigratedAt(port: String,
                                       terminal: String,
                                       shiftAssignmentsMigratedAt: Option[java.sql.Timestamp])(implicit ex: ExecutionContext): Future[Option[ShiftMeta]]

  def updateLastShiftAppliedAt(port: String,
                               terminal: String,
                               latestShiftAppliedAt: java.sql.Timestamp)(implicit ex: ExecutionContext): Future[Option[ShiftMeta]]
}

case class ShiftMetaInfoServiceImpl(shiftMetaInfoDao: ShiftMetaInfoDao) extends ShiftMetaInfoService {
  override def insertShiftMetaInfo(port: String,
                                   terminal: String,
                                   shiftAssignmentsMigratedAt: Option[java.sql.Timestamp],
                                   latestShiftAppliedAt: Option[java.sql.Timestamp])(implicit ex: ExecutionContext): Future[Int] =
    shiftMetaInfoDao.insertShiftMetaInfo(port, terminal, shiftAssignmentsMigratedAt, latestShiftAppliedAt)

  override def getShiftMetaInfo(port: String, terminal: String)(implicit ex: ExecutionContext): Future[Option[ShiftMeta]] =
    shiftMetaInfoDao.getShiftMetaInfo(port, terminal)
      .map(_.map(shiftMetaRow => ShiftMeta(shiftMetaRow.port, shiftMetaRow.terminal, shiftMetaRow.shiftAssignmentsMigratedAt, shiftMetaRow.latestShiftAppliedAt)))

  override def updateShiftAssignmentsMigratedAt(port: String,
                                                terminal: String,
                                                shiftAssignmentsMigratedAt: Option[java.sql.Timestamp])(implicit ex: ExecutionContext): Future[Option[ShiftMeta]] =
    shiftMetaInfoDao.updateShiftAssignmentsMigratedAt(port, terminal, shiftAssignmentsMigratedAt)
      .map(_.map(shiftMetaRow => ShiftMeta(shiftMetaRow.port, shiftMetaRow.terminal, shiftMetaRow.shiftAssignmentsMigratedAt, shiftMetaRow.latestShiftAppliedAt)))

  override def updateLastShiftAppliedAt(port: String,
                                        terminal: String,
                                        latestShiftAppliedAt: java.sql.Timestamp)(implicit ex: ExecutionContext): Future[Option[ShiftMeta]] =
    shiftMetaInfoDao.updateLastShiftAppliedAt(port, terminal, latestShiftAppliedAt)
      .map(_.map(shiftMetaRow => ShiftMeta(shiftMetaRow.port, shiftMetaRow.terminal, shiftMetaRow.shiftAssignmentsMigratedAt, shiftMetaRow.latestShiftAppliedAt)))
}