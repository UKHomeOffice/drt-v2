package uk.gov.homeoffice.drt.service.staffing

import drt.shared.{FixedPointAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch

import scala.concurrent.Future

trait FixedPointsService {
  def fixedPoints(maybePointInTime: Option[MillisSinceEpoch]): Future[FixedPointAssignments]

  def updateFixedPoints(assignments: Seq[StaffAssignmentLike]): Unit
}
