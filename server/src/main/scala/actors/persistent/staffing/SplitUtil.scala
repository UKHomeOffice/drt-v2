package actors.persistent.staffing

import drt.shared.{StaffAssignment, StaffAssignmentLike}
import uk.gov.homeoffice.drt.time.SDate
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


  def applyUpdatedShifts(existingAssignments: Seq[StaffAssignmentLike],
                         shiftsToUpdate: Seq[StaffAssignmentLike]): Seq[StaffAssignmentLike] = {

    def isOverlapping(existing: StaffAssignmentLike, update: StaffAssignmentLike): Boolean = {
      existing.terminal == update.terminal &&
        existing.start < update.end &&
        update.start < existing.end
    }


    val existingIntervals = existingAssignments.flatMap(splitIntoIntervals)
    val updateIntervals = shiftsToUpdate.flatMap(splitIntoIntervals)

    existingIntervals.foreach { existing =>
      println(s"existing: $existing")
    }

    updateIntervals.foreach { update =>
      println(s"update: $update")
    }

    val overallShift: Seq[StaffAssignment] = updateIntervals.foldLeft(existingIntervals.map(e => (e.terminal, e.start) -> e).toMap) { (acc, update) =>
      val overlappingKey = acc.keys.find { case (terminal, start) =>
        isOverlapping(acc((terminal, start)), update)
      }

      overlappingKey match {
        case Some(key) =>
          println(s"overlapping key: ${key._1} ${SDate(key._2).toISOString} update: ${update.terminal} ${SDate(update.start).toISOString} ${SDate(update.end).toISOString} ${update.numberOfStaff}")
          val existing = acc(key)
          val updated = existing.copy(numberOfStaff = update.numberOfStaff)
          acc.updated(key, updated)
          acc.updated(key, acc(key).copy(numberOfStaff = update.numberOfStaff))
        case None =>
          println(s"no overlapping key: ${update.terminal} ${SDate(update.start).toISOString} ${SDate(update.end).toISOString} ${update.numberOfStaff}")
          acc + ((update.terminal, update.start) -> update)
      }
    }.values.toSeq

    overallShift.sortBy(_.start)
  }

}
