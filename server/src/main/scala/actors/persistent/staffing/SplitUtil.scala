package actors.persistent.staffing

import SplitUtil.splitIntoIntervals
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{StaffAssignment, StaffAssignmentKey, StaffAssignmentLike}
import uk.gov.homeoffice.drt.ports.Terminals

import scala.collection.immutable
import scala.collection.immutable.TreeMap
import scala.concurrent.duration._
import scala.collection.parallel.CollectionConverters._
import scala.collection.immutable.{HashMap, TreeMap}
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


  def applyUpdatedShifts(existingAssignments: Seq[StaffAssignmentLike],
                         shiftsToUpdate: Seq[StaffAssignmentLike]): Seq[StaffAssignmentLike] = {

    val splitExisting = existingAssignments.flatMap(splitIntoIntervals)
    val splitUpdates = shiftsToUpdate.flatMap(splitIntoIntervals)

    val existingMap: Map[StaffAssignmentKey, StaffAssignment] = splitExisting.map { existing =>
      existing.key -> existing
    }.toMap

    val updatedAssignments = splitUpdates.foldLeft(existingMap) { (acc, update) =>
      acc.updated(update.key, update)
    }

    updatedAssignments.values.toSeq

  }

}
