package uk.gov.homeoffice.drt.service.staffing

import drt.shared.{MonthOfShifts, ShiftAssignments, StaffAssignmentLike}
import uk.gov.homeoffice.drt.time.LocalDate
import uk.gov.homeoffice.drt.time.MilliDate.MillisSinceEpoch

import scala.concurrent.Future

trait ShiftsService {
  def shiftsForDate(date: LocalDate, maybePointInTime: Option[MillisSinceEpoch]): Future[ShiftAssignments]

  def shiftsForMonth(month: MillisSinceEpoch): Future[MonthOfShifts]

  def updateShifts(shiftAssignments: Seq[StaffAssignmentLike]): Unit
}
