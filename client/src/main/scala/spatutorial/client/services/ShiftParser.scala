package drt.client.services

import java.util.UUID

import scala.collection.immutable.Seq
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}
import drt.client.services.JSDateConversions.SDate
import drt.client.services.JSDateConversions.SDate.JSSDate
import drt.shared.{MilliDate, SDateLike, StaffMovement}
import drt.shared.FlightsApi.TerminalName

object JSDateConversions {
  implicit def jsDateToMillis(jsDate: Date): Long = jsDate.getTime().toLong

  implicit def jsDateToMilliDate(jsDate: Date): MilliDate = MilliDate(jsDateToMillis(jsDate))

  implicit def jsSDateToMilliDate(jsSDate: SDateLike): MilliDate = MilliDate(jsSDate.millisSinceEpoch)

  implicit def longToMilliDate(millis: Long): MilliDate = MilliDate(millis)

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

      def millisSinceEpoch: Long = date.getTime().toLong

    }

    def getUTCDateFromDate(d: Date): Date = new Date(d.getTime() - (d.getTimezoneOffset() * 60000))

    def apply(milliDate: MilliDate): SDateLike = getUTCDateFromDate(new Date(milliDate.millisSinceEpoch))

    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0): SDateLike = getUTCDateFromDate(new Date(y, m - 1, d, h, mm))

    def parse(dateString: String): SDateLike = new Date(dateString)

    def today(): SDateLike = {
      val d = new Date()
      d.setHours(0)
      d.setMinutes(0)
      d.setMilliseconds(0)
      JSSDate(getUTCDateFromDate(d))
    }

    def now(): SDateLike = {
      val d = new Date()
      JSSDate(getUTCDateFromDate(d))
    }
  }

}

case class Shift(name: String, terminalName: TerminalName, startDt: MilliDate, endDt: MilliDate, numberOfStaff: Int) {
  def toCsv = {
    val startDate: SDateLike = SDate(startDt)
    val endDate: SDateLike = SDate(endDt)
    val startDateString = f"${startDate.getDate}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
    val startTimeString = f"${startDate.getHours}%02d:${startDate.getMinutes}%02d"
    val endTimeString = f"${endDate.getHours}%02d:${endDate.getMinutes}%02d"

    s"$name,$terminalName,$startDateString,$startTimeString,$endTimeString,$numberOfStaff"
  }
}

object Shift {

  import JSDateConversions._

  def apply(name: String, terminalName: TerminalName, startDate: String, startTime: String, endTime: String, numberOfStaff: String = "1"): Try[Shift] = {
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
      Shift(name, terminalName, startDt, adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt), staffDelta)
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

case class ShiftParser(rawShifts: String) {
  val lines = rawShifts.split("\n")
  val parsedShifts: Array[Try[Shift]] = lines.map(l => {
    l.replaceAll("([^\\\\]),", "$1\",\"").split("\",\"").toList.map(_.trim)
  })
    .filter(parts => parts.length == 5 || parts.length == 6)
    .map {
      case List(description, terminalName, startDay, startTime, endTime) =>
        Shift(description, terminalName, startDay, startTime, endTime)
      case List(description, terminalName, startDay, startTime, endTime, staffNumberDelta) =>
        Shift(description, terminalName, startDay, startTime, endTime, staffNumberDelta)
    }
}

case class ShiftService(shifts: Seq[Shift]) {
  def staffAt(date: MilliDate): Int = shifts.filter(shift =>
    (shift.startDt <= date && date <= shift.endDt)).map(_.numberOfStaff).sum
  def terminalStaffAt(terminalName: TerminalName, date: MilliDate): Int = shifts.filter(shift => {
    shift.startDt <= date && date <= shift.endDt && shift.terminalName == terminalName
  }).map(_.numberOfStaff).sum
}

object ShiftService {
  def apply(shifts: Seq[Try[Shift]]): Try[ShiftService] = {
    if (shifts.exists(_.isFailure))
      Failure(new Exception("Couldn't parse shifts"))
    else {
      val successfulShifts = shifts.map { case Success(s) => s }
      Success(ShiftService(successfulShifts))
    }
  }
}

object StaffMovements {
  def shiftsToMovements(shifts: Seq[Shift]) = {
    shifts.flatMap(shift => {
      val uuid: UUID = UUID.randomUUID()
      StaffMovement(shift.terminalName, shift.name + " start", time = shift.startDt, shift.numberOfStaff, uuid) ::
        StaffMovement(shift.terminalName, shift.name + " end", time = shift.endDt, -shift.numberOfStaff, uuid) :: Nil
    }).sortBy(_.time)
  }

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTime: MilliDate) = movements.takeWhile(_.time <= dateTime).map(_.delta).sum

  def staffAt(shiftService: ShiftService)(movements: Seq[StaffMovement])(dateTime: MilliDate) = {
    val baseStaff = shiftService.staffAt(dateTime)
    baseStaff + adjustmentsAt(movements)(dateTime)
  }

  def terminalStaffAt(shiftService: ShiftService)(movements: Seq[StaffMovement])(terminalName: TerminalName, dateTime: MilliDate) = {
    val baseStaff = shiftService.terminalStaffAt(terminalName, dateTime)
    baseStaff + adjustmentsAt(movements.filter(_.terminalName == terminalName))(dateTime)
  }
}
