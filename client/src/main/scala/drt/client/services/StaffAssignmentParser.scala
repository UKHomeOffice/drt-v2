package drt.client.services

import java.util.UUID

import drt.client.services.JSDateConversions.SDate
import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.TerminalName
import drt.shared.{MilliDate, SDateLike, StaffMovement}

import scala.collection.immutable.Seq
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}
import scala.language.implicitConversions

object JSDateConversions {
  implicit def jsDateToMillis(jsDate: Date): Long = jsDate.getTime().toLong

  implicit def jsDateToMilliDate(jsDate: Date): MilliDate = MilliDate(jsDateToMillis(jsDate))

  implicit def jsSDateToMilliDate(jsSDate: SDateLike): MilliDate = MilliDate(jsSDate.millisSinceEpoch)

  implicit def longToMilliDate(millis: Long): MilliDate = MilliDate(millis)

  implicit def milliDateToSDate(milliDate: MilliDate): SDateLike = SDate(milliDate)

  implicit def jsDateToSDate(date: Date): SDateLike = JSSDate(date)

  object SDate {

    case class JSSDate(date: Date) extends SDateLike {

      def getFullYear(): Int = date.getFullYear()

      // js Date Months are 0 based, but joda dates are 1 based. We've decided to match joda, because it is sane.
      def getMonth(): Int = date.getMonth() + 1

      def getDate(): Int = date.getDate()

      def getHours(): Int = date.getHours()

      def getMinutes(): Int = date.getMinutes()

      def addDays(daysToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setDate(newDate.getDate() + daysToAdd)
        newDate
      }

      def addHours(hoursToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setHours(newDate.getHours() + hoursToAdd)
        newDate
      }

      def addMinutes(minutesToAdd: Int): SDateLike = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setMinutes(newDate.getMinutes() + minutesToAdd)
        newDate
      }

      def millisSinceEpoch: Long = date.getTime().toLong

      override def toISOString(): String = date.toISOString()

      def getDayOfWeek(): Int = if (date.getDay() == 0) 7 else date.getDay()
    }

    def apply(milliDate: MilliDate): SDateLike = new Date(milliDate.millisSinceEpoch)

    /** **
      * Beware - in JS land, this is interpreted as Local time, but the parse will interpret the timezone component
      */
    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0): SDateLike = new Date(y, m - 1, d, h, mm)

    /** *
      * dateString is an ISO parseable datetime representation, with optional timezone
      *
      * @param dateString
      * @return
      */
    def apply(dateString: String): SDateLike = new Date(dateString)
    def parse(dateString: String): SDateLike = new Date(dateString)
    def stringToSDateLikeOption(dateString: String): Option[SDateLike] = Try(SDate(dateString)).toOption

    def midnightThisMorning(): SDateLike = {
      val d = new Date()
      d.setHours(0)
      d.setMinutes(0)
      d.setSeconds(0)
      d.setMilliseconds(0)
      JSSDate(d)
    }

    def now(): SDateLike = {
      JSSDate(new Date())
    }
  }
}

case class StaffAssignment(name: String, terminalName: TerminalName, startDt: MilliDate, endDt: MilliDate, numberOfStaff: Int) {
  def toCsv: String = {
    val startDate: SDateLike = SDate(startDt)
    val endDate: SDateLike = SDate(endDt)
    val startDateString = f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
    val startTimeString = f"${startDate.getHours()}%02d:${startDate.getMinutes()}%02d"
    val endTimeString = f"${endDate.getHours()}%02d:${endDate.getMinutes()}%02d"

    s"$name,$terminalName,$startDateString,$startTimeString,$endTimeString,$numberOfStaff"
  }
}

object StaffAssignment {

  import JSDateConversions._

  def apply(name: String, terminalName: TerminalName, startDate: String, startTime: String, endTime: String, numberOfStaff: String = "1"): Try[StaffAssignment] = {
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
      StaffAssignment(name, terminalName, startDt, adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt), staffDelta)
    }
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
  val lines: Array[TerminalName] = rawStaffAssignments.split("\n")
  val parsedAssignments: Array[Try[StaffAssignment]] = lines.map(l => {
    l.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim)
  })
    .filter(parts => parts.length == 5 || parts.length == 6)
    .map {
      case List(description, terminalName, startDay, startTime, endTime) =>
        StaffAssignment(description, terminalName, startDay, startTime, endTime)
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        StaffAssignment(description, terminalName, startDay, startTime, endTime, staffNumberDelta)
    }
}

trait StaffAssignmentService {

  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int
}

case class StaffAssignmentServiceWithoutDates(assignments: Seq[StaffAssignment]) extends StaffAssignmentService {

  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int = assignments.filter(assignment => {
    assignment.terminalName == terminalName &&
      SDate(date).toHoursAndMinutes() >= SDate(assignment.startDt).toHoursAndMinutes() &&
      SDate(date).toHoursAndMinutes() <= SDate(assignment.endDt).toHoursAndMinutes()
  }).map(_.numberOfStaff).sum
}

case class StaffAssignmentServiceWithDates(assignments: Seq[StaffAssignment]) extends StaffAssignmentService {

  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int = assignments.filter(assignment => {
    assignment.startDt <= date && date <= assignment.endDt && assignment.terminalName == terminalName
  }).map(_.numberOfStaff).sum
}

object StaffAssignmentServiceWithoutDates {
  def apply(assignments: Seq[Try[StaffAssignment]]): Try[StaffAssignmentServiceWithoutDates] = {
    if (assignments.exists(_.isFailure))
      Failure(new Exception("Couldn't parse assignments"))
    else {
      Success(StaffAssignmentServiceWithoutDates(assignments.collect { case Success(s) => s }))
    }
  }
}

object StaffAssignmentServiceWithDates {
  def apply(assignments: Seq[Try[StaffAssignment]]): Try[StaffAssignmentServiceWithDates] = {
    if (assignments.exists(_.isFailure))
      Failure(new Exception("Couldn't parse assignments"))
    else {
      Success(StaffAssignmentServiceWithDates(assignments.collect { case Success(s) => s }))
    }
  }
}

object StaffMovements {
  def assignmentsToMovements(staffAssignments: Seq[StaffAssignment]): Seq[StaffMovement] = {
    staffAssignments.flatMap(assignment => {
      val uuid: UUID = UUID.randomUUID()
      StaffMovement(assignment.terminalName, assignment.name + " start", time = assignment.startDt, assignment.numberOfStaff, uuid) ::
        StaffMovement(assignment.terminalName, assignment.name + " end", time = assignment.endDt, -assignment.numberOfStaff, uuid) :: Nil
    }).sortBy(_.time)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTime: MilliDate): Int = movements.takeWhile(_.time <= dateTime).map(_.delta).sum

  def terminalStaffAt(assignmentService: StaffAssignmentService, fixedPointService: StaffAssignmentServiceWithoutDates)(movements: Seq[StaffMovement])(terminalName: TerminalName, dateTime: MilliDate): Int = {
    val baseStaff = assignmentService.terminalStaffAt(terminalName, dateTime)
    val fixedPointStaff = fixedPointService.terminalStaffAt(terminalName, dateTime)

    val movementAdjustments = adjustmentsAt(movements.filter(_.terminalName == terminalName))(dateTime)
    baseStaff - fixedPointStaff + movementAdjustments
  }
}
