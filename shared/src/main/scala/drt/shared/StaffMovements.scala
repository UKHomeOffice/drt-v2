package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDateLike}

object StaffMovements {
  def assignmentsToMovements(staffAssignments: Seq[StaffAssignment]): Seq[StaffMovement] = {
    staffAssignments.flatMap(assignment => {
      val uuid = UUID.randomUUID().toString()
      StaffMovement(assignment.terminal, assignment.name + " start", time = assignment.start, assignment.numberOfStaff, uuid, createdBy = assignment.createdBy) ::
        StaffMovement(assignment.terminal, assignment.name + " end", time = assignment.end, -assignment.numberOfStaff, uuid, createdBy = assignment.createdBy) :: Nil
    }).sortBy(_.time)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTime: SDateLike): Int = {
    val minuteSinceEpoch = dateTime.millisSinceEpoch / 60000
    movements
      .sortBy(_.time)
      .takeWhile(_.time / 60000 <= minuteSinceEpoch)
      .map(_.delta)
      .sum
  }

  def terminalStaffAt(shiftAssignments: ShiftAssignments)
                     (movements: Seq[StaffMovement])
                     (terminalName: Terminal, dateTime: SDateLike, msToSd: MillisSinceEpoch => SDateLike): Int = {
    val baseStaff = shiftAssignments.terminalStaffAt(terminalName, dateTime, msToSd)

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminal == terminalName))(dateTime)
    baseStaff + movementAdjustments
  }

  val empty: StaffMovements = StaffMovements(Seq())
}

case class StaffMovements(movements: Seq[StaffMovement]) extends HasExpireables[StaffMovements] {
  def +(movementsToAdd: Seq[StaffMovement]): StaffMovements =
    copy(movements = movements ++ movementsToAdd)

  def -(movementsToRemove: Seq[String]): StaffMovements =
    copy(movements = movements.filterNot(sm => movementsToRemove.contains(sm.uUID)))

  def purgeExpired(expireBefore: () => SDateLike): StaffMovements = {
    val expireBeforeMillis = expireBefore().millisSinceEpoch
    val unexpiredPairsOfMovements = movements
      .groupBy(_.uUID)
      .values
      .filter(pair => {
        val neitherHaveExpired = pair.exists(!_.isExpired(expireBeforeMillis))
        neitherHaveExpired
      })
      .flatten.toSeq
    copy(movements = unexpiredPairsOfMovements)
  }

  def forDay(day: LocalDate)
            (implicit toSDate: LocalDate => SDateLike): Seq[StaffMovement] = {
    val startOfDayMillis = day.getLocalLastMidnight.millisSinceEpoch
    val endOfDayMillis = day.getLocalNextMidnight.millisSinceEpoch

    movements
      .groupBy(_.uUID)
      .filter { case (_, movementsPair) => areInWindow(startOfDayMillis, endOfDayMillis, movementsPair) }
      .values
      .flatten
      .toSeq
  }

  def areInWindow(startOfDayMillis: MillisSinceEpoch,
                  endOfDayMillis: MillisSinceEpoch,
                  movementsPair: Seq[StaffMovement]): Boolean = {
    val chronologicalMovementsPair = movementsPair.sortBy(_.time).toList

    chronologicalMovementsPair match {
      case singleMovement :: Nil =>
        val movementMillis = singleMovement.time
        isInWindow(startOfDayMillis, endOfDayMillis, movementMillis)

      case start :: end :: Nil =>
        val firstInWindow = isInWindow(startOfDayMillis, endOfDayMillis, start.time)
        val lastInWindow = isInWindow(startOfDayMillis, endOfDayMillis, end.time)
        firstInWindow || lastInWindow

      case _ => false
    }
  }

  def isInWindow(startOfDayMillis: MillisSinceEpoch,
                 endOfDayMillis: MillisSinceEpoch,
                 movementMillis: MillisSinceEpoch): Boolean = {
    startOfDayMillis <= movementMillis && movementMillis <= endOfDayMillis
  }
}
