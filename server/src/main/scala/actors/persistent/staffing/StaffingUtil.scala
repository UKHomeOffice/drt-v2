package actors.persistent.staffing

import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import uk.gov.homeoffice.drt.Shift
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonTimeZone
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike}

object StaffingUtil {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def generateDailyAssignments(shift: Shift): Seq[StaffAssignment] = {
    val (startHH, startMM) = shift.startTime.split(":") match {
      case Array(hh, mm) => (hh.toInt, mm.toInt)
    }
    val (endHH, endMM) = shift.endTime.split(":") match {
      case Array(hh, mm) => (hh.toInt, mm.toInt)
    }

    val startDate = SDate(shift.startDate.year, shift.startDate.month, shift.startDate.day, 0, 0, europeLondonTimeZone)
    val endDate = shift.endDate.map(ed => SDate(ed.year, ed.month, ed.day, 0, 0, europeLondonTimeZone)).getOrElse(startDate.addMonths(6))

    val daysBetween = startDate.daysBetweenInclusive(endDate) - 1
    (0 to daysBetween).map { day =>
      val currentDate: SDateLike = startDate.addDays(day)
      val startMillis = SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, startHH, startMM, europeLondonTimeZone).millisSinceEpoch
      val isShiftEndAfterMidNight = endHH < startHH || (endHH == startHH && endMM < startMM)
      val endMillis = if (isShiftEndAfterMidNight) {
        SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          europeLondonTimeZone).addDays(1).millisSinceEpoch
      } else {
        SDate(currentDate.getFullYear, currentDate.getMonth, currentDate.getDate, endHH, endMM,
          europeLondonTimeZone).millisSinceEpoch
      }

      StaffAssignment(
        name = shift.shiftName,
        terminal = Terminal(shift.terminal),
        start = startMillis,
        end = endMillis,
        numberOfStaff = shift.staffNumber,
        createdBy = shift.createdBy
      )
    }
  }

   def staffAssignmentsSlotSummaries(shiftAssignments: Seq[StaffAssignment]): Map[TM, StaffAssignment] =
    shiftAssignments
      .flatMap(_.splitIntoSlots(ShiftAssignments.periodLengthMinutes))
      .groupBy(a => TM(a.terminal, a.start))
      .map { case (tm, assignments) =>
        val combinedAssignment = assignments.reduce { (a1, a2) =>
          a1.copy(numberOfStaff = a1.numberOfStaff + a2.numberOfStaff)
        }
        tm -> combinedAssignment
      }


  def updateAssignmentsForShiftChange(previousShift: Shift,
                                      overridingShift: Seq[Shift],
                                      futureExistingShift: Option[Shift],
                                      newShift: Shift,
                                      allShifts: ShiftAssignments
                                     ): Seq[StaffAssignmentLike] = {

    val newShiftsStaff: Seq[StaffAssignment] = generateDailyAssignments(newShift)
    val newShiftSplitDailyAssignments: Map[TM, StaffAssignment] = staffAssignmentsSlotSummaries(newShiftsStaff)
    val overridingAssignments: Seq[StaffAssignment] = getOverridingAssignments(overridingShift, newShift)
    val overridingShiftAssignments: Map[TM, StaffAssignment] = staffAssignmentsSlotSummaries(overridingAssignments)
    val futureExistingShiftStaff: Seq[StaffAssignment] = futureExistingShift
      .map(generateDailyAssignments)
      .getOrElse(Seq.empty)
    val futureExistingShiftSplitDailyAssignments: Map[TM, StaffAssignment] = staffAssignmentsSlotSummaries(futureExistingShiftStaff)
    val isTimeChange = previousShift.startTime != newShift.startTime || previousShift.endTime != newShift.endTime
    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap
    val updatedNewShiftsAssignments = getUpdatedNewShiftsAssignments(
      newShiftSplitDailyAssignments = newShiftSplitDailyAssignments,
      existingAllAssignments = existingAllAssignments,
      overridingShiftAssignments = overridingShiftAssignments,
      futureExistingShiftSplitDailyAssignments = futureExistingShiftSplitDailyAssignments,
      previousShift = previousShift,
      newShift = newShift,
      isTimeChange = isTimeChange
    )

    if (isTimeChange)
      timeChangeMerge(
        overridingShiftAssignments,
        newShiftSplitDailyAssignments,
        updatedNewShiftsAssignments,
        existingAllAssignments,
        previousShift,
        newShift
      )
    else
      updatedNewShiftsAssignments
  }

   def getOverridingAssignments(overridingShift: Seq[Shift],
                                       newShift: Shift
                                      ): Seq[StaffAssignment] = {
    overridingShift
      .filterNot(s =>
        s.port == newShift.port &&
          s.terminal == newShift.terminal &&
          s.shiftName == newShift.shiftName &&
          s.startDate == newShift.startDate
      )
      .flatMap(os => generateDailyAssignments(os))
  }

  private def getUpdatedNewShiftsAssignments(newShiftSplitDailyAssignments: Map[TM, StaffAssignment],
                                             existingAllAssignments: Map[TM, StaffAssignmentLike],
                                             overridingShiftAssignments: Map[TM, StaffAssignment],
                                             futureExistingShiftSplitDailyAssignments: Map[TM, StaffAssignment],
                                             previousShift: Shift,
                                             newShift: Shift,
                                             isTimeChange: Boolean
                                            ): Seq[StaffAssignmentLike] = {
    def findOverridingShift(assignment: StaffAssignment): Option[Int] =
      overridingShiftAssignments.get(TM(assignment.terminal, assignment.start)).map(_.numberOfStaff)

    def findFutureExistingShift(assignment: StaffAssignment): Option[Int] =
      futureExistingShiftSplitDailyAssignments.get(TM(assignment.terminal, assignment.start)).map(_.numberOfStaff)

    newShiftSplitDailyAssignments.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            findOverridingShift(assignment).map { overridingStaff =>
              findFutureExistingShift(assignment).map { futureExistingStaff =>
                if (existing.numberOfStaff == overridingStaff + futureExistingStaff)
                  assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
                else if (existing.numberOfStaff == overridingStaff)
                  assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
                else if (existing.numberOfStaff != futureExistingStaff)
                  existing
                else
                  assignment
              }.getOrElse {
                if (existing.numberOfStaff == overridingStaff + previousShift.staffNumber)
                  assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
                else if (existing.numberOfStaff == previousShift.staffNumber)
                  assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
                else if (existing.numberOfStaff == overridingStaff)
                  assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
                else if (existing.numberOfStaff == 0)
                  assignment
                else
                  existing
              }
            }.getOrElse {
              findFutureExistingShift(assignment).map { futureExistingStaff =>
                if (existing.numberOfStaff == futureExistingStaff)
                  assignment.copy(numberOfStaff = newShift.staffNumber)
                else
                  existing
              }.getOrElse {
                if (existing.numberOfStaff == previousShift.staffNumber || existing.numberOfStaff == 0)
                  assignment
                else
                  existing
              }
            }

          case None => assignment
        }
    }.toSeq.sortBy(_.start)
  }

  private def getMillisSinceEpoch(startDate: LocalDate): Long = {
    SDate(startDate.year, startDate.month, startDate.day, 0, 0, europeLondonTimeZone).millisSinceEpoch
  }

  private def timeChangeMerge(overridingShiftAssignments: Map[TM, StaffAssignment],
                              newShiftSplitDailyAssignments: Map[TM, StaffAssignment],
                              updatedNewShiftsAssignments: Seq[StaffAssignmentLike],
                              existingAllAssignments: Map[TM, StaffAssignmentLike],
                              previousShift: Shift,
                              newShift: Shift
                             ): Seq[StaffAssignmentLike] = {
    val timeChangedOverridingShiftAssignments: Seq[StaffAssignmentLike] =
      overridingShiftAssignments.map {
        case (tm, overridingAssignment) =>
          updatedNewShiftsAssignments.find(a => a.terminal == overridingAssignment.terminal && a.start == overridingAssignment.start) match {
            case Some(updatedAssignments) => updatedAssignments
            case None =>
              existingAllAssignments.get(tm) match {
                case Some(existingAssignment) =>
                  if ((existingAssignment.numberOfStaff == overridingAssignment.numberOfStaff + previousShift.staffNumber) && existingAssignment.start > getMillisSinceEpoch(newShift.startDate))
                    overridingAssignment
                  else if (existingAssignment.numberOfStaff == overridingAssignment.numberOfStaff + newShift.staffNumber && existingAssignment.start > getMillisSinceEpoch(newShift.startDate))
                    overridingAssignment
                  else existingAssignment
                case None => overridingAssignment
              }
          }
      }.toSeq

    val timeChangedMap = timeChangedOverridingShiftAssignments.map(a => TM(a.terminal, a.start) -> a).toMap
    val newShiftMap = updatedNewShiftsAssignments.map(a => TM(a.terminal, a.start) -> a).toMap
    val allKeys = timeChangedMap.keySet ++ newShiftMap.keySet

    allKeys.toSeq.sorted.map { tm =>
      timeChangedMap.getOrElse(tm, newShiftMap(tm))
    }
  }

  def updateWithShiftDefaultStaff(shifts: Seq[Shift], allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {

    val allShiftsStaff: Seq[StaffAssignment] = shifts.flatMap { shift =>
      generateDailyAssignments(shift)
    }

    val splitDailyAssignmentsWithOverlap = staffAssignmentsSlotSummaries(allShiftsStaff)

    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap

    splitDailyAssignmentsWithOverlap.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            if (existing.numberOfStaff == 0) assignment else existing
          case None => assignment
        }
    }.toSeq.sortBy(_.start)

  }

  def addAssignments(newShift: Shift,
                     overridingShift: Seq[Shift],
                     allShifts: ShiftAssignments): Seq[StaffAssignmentLike] = {

    val newShiftsStaff: Seq[StaffAssignment] = generateDailyAssignments(newShift)
    val newShiftSplitDailyAssignments: Map[TM, StaffAssignment] = staffAssignmentsSlotSummaries(newShiftsStaff)
    val overridingAssignments: Seq[StaffAssignment] = getOverridingAssignments(overridingShift, newShift)
    val overridingShiftAssignments: Map[TM, StaffAssignment] = staffAssignmentsSlotSummaries(overridingAssignments)
    val existingAllAssignments = allShifts.assignments.map(a => TM(a.terminal, a.start) -> a).toMap
    val updatedNewShiftsAssignments = getUpdatedNewShiftAssignments(
      newShiftSplitDailyAssignments = newShiftSplitDailyAssignments,
      existingAllAssignments = existingAllAssignments,
      overridingShiftAssignments = overridingShiftAssignments,
      newShift = newShift
    )

    updatedNewShiftsAssignments
  }

  private def getUpdatedNewShiftAssignments(newShiftSplitDailyAssignments: Map[TM, StaffAssignment],
                                            existingAllAssignments: Map[TM, StaffAssignmentLike],
                                            overridingShiftAssignments: Map[TM, StaffAssignment],
                                            newShift: Shift,
                                           ): Seq[StaffAssignmentLike] = {
    def findOverridingShift(assignment: StaffAssignment): Option[Int] =
      overridingShiftAssignments.get(TM(assignment.terminal, assignment.start)).map(_.numberOfStaff)

    newShiftSplitDailyAssignments.map {
      case (tm, assignment) =>
        existingAllAssignments.get(tm) match {
          case Some(existing) =>
            findOverridingShift(assignment).map { overridingStaff =>
              if (existing.numberOfStaff == overridingStaff)
                assignment.copy(numberOfStaff = overridingStaff + newShift.staffNumber)
              else
                assignment
            }.getOrElse {
              if (existing.numberOfStaff == 0)
                assignment
              else
                existing
            }
          case None => assignment
        }
    }.toSeq.sortBy(_.start)
  }

}
