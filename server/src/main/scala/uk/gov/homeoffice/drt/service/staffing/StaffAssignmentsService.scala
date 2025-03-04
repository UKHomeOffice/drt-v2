package uk.gov.homeoffice.drt.service.staffing

import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch

import scala.concurrent.Future

trait StaffAssignmentsService {
  def staffAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def allStaffAssignments: Future[ShiftAssignments]

  def updateStaffAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments]
}
