package uk.gov.homeoffice.drt.service.staffing

import drt.shared.{ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch

import scala.concurrent.Future

trait ShiftAssignmentsService {
  def shiftAssignmentsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def allShiftAssignments: Future[ShiftAssignments]

  def updateShiftAssignments(shiftAssignments: Seq[StaffAssignmentLike]): Future[ShiftAssignments]
}
