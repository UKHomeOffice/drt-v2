package actors.persistent.staffing

import drt.shared.{StaffAssignment, StaffAssignmentKey, StaffAssignmentLike}

import scala.concurrent.duration._

object SplitUtil {

  def splitIntoIntervals(assignment: StaffAssignmentLike): Seq[StaffAssignment] = {
    val intervalMillis = 14.minutes.toMillis
    val breakMillis = 1.minute.toMillis
    val intervals = for {
      start <- assignment.start until assignment.end by (intervalMillis + breakMillis)
    } yield StaffAssignment(
      name = assignment.name,
      terminal = assignment.terminal,
      start = start,
      end = Math.min(start + intervalMillis, assignment.end),
      numberOfStaff = assignment.numberOfStaff,
      createdBy = assignment.createdBy
    )
    intervals
  }
}
