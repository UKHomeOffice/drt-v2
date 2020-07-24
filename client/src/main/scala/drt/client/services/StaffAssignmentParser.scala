package drt.client.services

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
    s"${assignment.name},${assignment.terminal},${startDate.ddMMyyString},${startDate.toHoursAndMinutes},${endDate.toHoursAndMinutes},${assignment.numberOfStaff}"
  }

  def fixedPointsFormat(fixedPoints: FixedPointAssignments): String = fixedPoints.assignments.map(fixedPointFormat).mkString("\n")

  def fixedPointFormat(assignment: StaffAssignment): String = {
    val startDate: SDateLike = SDate(assignment.startDt)
    val endDate: SDateLike = SDate(assignment.endDt)
    s"${assignment.name}, ${startDate.toHoursAndMinutes}, ${endDate.toHoursAndMinutes}, ${assignment.numberOfStaff}"
  }

  private def adjustEndDateIfEndTimeIsBeforeStartTime(d: Int, m: Int, y: Int, startDt: SDateLike, endDt: SDateLike): SDateLike =
    if (endDt.millisSinceEpoch < startDt.millisSinceEpoch)
      SDate(y, m, d, endDt.getHours(), endDt.getMinutes()).addDays(1)
    else
      endDt

  private def parseTimeWithStartTime(startTime: String, d: Int, m: Int, y: Int): Try[SDateLike] = Try {
    val startT = startTime.split(":").toVector
    val (startHour, startMinute) = (startT(0).toInt, startT(1).toInt)
    val startDt = SDate(y, m, d, startHour, startMinute)
    startDt
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
