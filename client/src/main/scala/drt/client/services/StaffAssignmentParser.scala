package drt.client.services

import java.util.UUID

import drt.shared.Terminals.Terminal
import drt.shared._

import scala.util.Try


object StaffAssignmentHelper {

  import JSDateConversions._

  def tryStaffAssignment(name: String, terminalName: String, startDate: String, startTime: String, endTime: String, numberOfStaff: String, createdBy: Option[String]): Try[StaffAssignment] = {
    val staffDeltaTry = Try(numberOfStaff.toInt)
    val ymd = startDate.split("/").toVector

    val tryDMY: Try[(Int, Int, Int)] = Try((ymd(0).toInt, ymd(1).toInt, ymd(2).toInt + 2000))

    for {
      dmy <- tryDMY
      (d, m, y) = dmy

      startDtTry: Try[SDateLike] = parseTimeWithStartTime(startTime, d, m, y)
      endDtTry: Try[SDateLike] = parseTimeWithStartTime(endTime, d, m, y)
      startDt <- startDtTry
      endDt <- endDtTry
      staffDelta: Int <- staffDeltaTry
    } yield {
      StaffAssignment(name, Terminal(terminalName), startDt, adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt), staffDelta, createdBy = createdBy)
    }
  }

  def tryStaffAssignment(name: String, terminalName: String, startDate: String, startTime: String, lengthOfTimeMinutes: Int, numberOfStaff: String, createdBy: Option[String]): Try[StaffAssignment] = {
    val staffDeltaTry = Try(numberOfStaff.toInt)
    val ymd = startDate.split("/").toVector

    val tryDMY: Try[(Int, Int, Int)] = Try((ymd(0).toInt, ymd(1).toInt, ymd(2).toInt + 2000))

    for {
      dmy <- tryDMY
      (d, m, y) = dmy

      startDtTry: Try[SDateLike] = parseTimeWithStartTime(startTime, d, m, y)
      startDt <- startDtTry
      staffDelta: Int <- staffDeltaTry
    } yield {
      val endDt = startDt.addMinutes(lengthOfTimeMinutes)
      StaffAssignment(name, Terminal(terminalName), startDt, adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt), staffDelta, createdBy = createdBy)
    }
  }

  def toCsv(assignment: StaffAssignment): String = {
    val startDate: SDateLike = SDate(assignment.startDt)
    val endDate: SDateLike = SDate(assignment.endDt)
    val startDateString = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
    val startTimeString = f"${startDate.getHours()}%02d:${startDate.getMinutes()}%02d"
    val endTimeString = f"${endDate.getHours()}%02d:${endDate.getMinutes()}%02d"

    s"${assignment.name},${assignment.terminal},$startDateString,$startTimeString,$endTimeString,${assignment.numberOfStaff}"
  }

  def fixedPointsFormat(fixedPoints: FixedPointAssignments): String = fixedPoints.assignments.map(fixedPointFormat).mkString("\n")

  def fixedPointFormat(assignment: StaffAssignment): String = {
    val startDate: SDateLike = SDate(assignment.startDt)
    val endDate: SDateLike = SDate(assignment.endDt)
    val startTimeString = f"${startDate.getHours()}%02d:${startDate.getMinutes()}%02d"
    val endTimeString = f"${endDate.getHours()}%02d:${endDate.getMinutes()}%02d"

    s"${assignment.name}, $startTimeString, $endTimeString, ${assignment.numberOfStaff}"
  }

  private def adjustEndDateIfEndTimeIsBeforeStartTime(d: Int, m: Int, y: Int, startDt: SDateLike, endDt: SDateLike): SDateLike = {
    if (endDt.millisSinceEpoch < startDt.millisSinceEpoch) {
      SDate(y, m, d, endDt.getHours(), endDt.getMinutes()).addDays(1)
    }
    else {
      endDt
    }
  }

  private def parseTimeWithStartTime(startTime: String, d: Int, m: Int, y: Int): Try[SDateLike] = {
    Try {
      val startT = startTime.split(":").toVector
      val (startHour, startMinute) = (startT(0).toInt, startT(1).toInt)
      val startDt = SDate(y, m, d, startHour, startMinute)
      startDt
    }
  }
}

case class StaffAssignmentParser(rawStaffAssignments: String) {
  val parsedAssignments: Array[Try[StaffAssignment]] = rawStaffAssignments
    .split("\n")
    .map(_.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim))
    .filter(parts => parts.length == 5 || parts.length == 6)
    .map {
      case List(description, terminalName, startDay, startTime, endTime) =>
        StaffAssignmentHelper.tryStaffAssignment(description, terminalName, startDay, startTime, endTime, "1", None)
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        StaffAssignmentHelper.tryStaffAssignment(description, terminalName, startDay, startTime, endTime, staffNumberDelta, None)
    }
}

object StaffMovements {
  def assignmentsToMovements(staffAssignments: Seq[StaffAssignment]): Seq[StaffMovement] = {
    staffAssignments.flatMap(assignment => {
      val uuid: UUID = UUID.randomUUID()
      StaffMovement(assignment.terminal, assignment.name + " start", time = assignment.startDt, assignment.numberOfStaff, uuid, createdBy = assignment.createdBy) ::
        StaffMovement(assignment.terminal, assignment.name + " end", time = assignment.endDt, -assignment.numberOfStaff, uuid, createdBy = assignment.createdBy) :: Nil
    }).sortBy(_.time.millisSinceEpoch)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTime: SDateLike): Int = movements.sortBy(_.time.millisSinceEpoch).takeWhile(_.time.millisSinceEpoch <= dateTime.millisSinceEpoch).map(_.delta).sum

  def terminalStaffAt(shiftAssignments: ShiftAssignments)(movements: Seq[StaffMovement])(terminalName: Terminal, dateTime: SDateLike): Int = {
    val baseStaff = shiftAssignments.terminalStaffAt(terminalName, dateTime)

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminal == terminalName))(dateTime)
    baseStaff + movementAdjustments
  }
}
