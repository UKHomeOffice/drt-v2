package actors.persistent.staffing

import SplitUtil.splitIntoIntervals
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{StaffAssignment, StaffAssignmentLike}
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
    def isOverlapping(existing: StaffAssignmentLike, update: StaffAssignmentLike): Boolean = {
      existing.terminal == update.terminal &&
        existing.start < update.end &&
        update.start < existing.end
    }

    implicit val ordering: Ordering[Long] = Ordering.Long

    val existingIntervals: immutable.Iterable[StaffAssignment] = existingAssignments
      .groupBy(_.terminal)
      .par
      .flatMap { case (_, assignments) => assignments.flatMap(splitIntoIntervals) }
      .seq

    val updateIntervals: immutable.Iterable[StaffAssignment] = shiftsToUpdate
      .groupBy(_.terminal)
      .par
      .flatMap { case (_, assignments) => assignments.flatMap(splitIntoIntervals) }
      .seq


    val existingMap: Map[Terminals.Terminal, TreeMap[Long, StaffAssignment]] = existingIntervals.groupBy(_.terminal).view.mapValues { assignments =>
      TreeMap(assignments.map(e => e.start -> e).toSeq: _*)
    }.toMap

    val overallShift: Seq[StaffAssignment] = updateIntervals.foldLeft(existingMap) { (acc, update) =>
      val terminalMap = acc.getOrElse(update.terminal, TreeMap.empty[Long, StaffAssignment])
      val overlappingKey = terminalMap.keys.find { start =>
        isOverlapping(terminalMap(start), update)
      }

      overlappingKey match {
        case Some(start) =>
          val existing = terminalMap(start)
          val updated = existing.copy(numberOfStaff = update.numberOfStaff)
          acc.updated(update.terminal, terminalMap.updated(start, updated))
        case None =>
          acc.updated(update.terminal, terminalMap + (update.start -> update))
      }
    }.values.flatMap(_.values).toSeq

    overallShift
  }

}