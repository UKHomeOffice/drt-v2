package spatutorial.client.services

import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.client.services.JSDateConversions.SDate.JSSDate
import spatutorial.client.services.StaffMovements.StaffMovement
import spatutorial.shared.FlightsApi._
import spatutorial.shared.{MilliDate, SDate}

import scala.collection.immutable.Seq
import scala.scalajs.js.Date
import scala.util.{Failure, Success, Try}


object JSDateConversions {
  implicit def jsDateToMillis(jsDate: Date): Long = jsDate.getTime().toLong

  implicit def jsDateToMilliDate(jsDate: Date): MilliDate = MilliDate(jsDateToMillis(jsDate))

  implicit def jsSDateToMilliDate(jsSDate: SDate): MilliDate = MilliDate(jsSDate.millisSinceEpoch)

  implicit def longToMilliDate(millis: Long): MilliDate = MilliDate(millis)

  implicit def jsDateToSDate(date: Date): SDate = JSSDate(date)

  object SDate {

    case class JSSDate(date: Date) extends SDate {

      def getFullYear(): Int = date.getFullYear()

      // js Date Months are 0 based, but joda dates are 1 based. We've decided to match joda, because it is sane.
      def getMonth(): Int = date.getMonth() + 1

      def getDate(): Int = date.getDate()

      def getHours(): Int = date.getHours()

      def getMinutes(): Int = date.getMinutes()

      def addDays(daysToAdd: Int): SDate = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setDate(newDate.getDate() + daysToAdd)
        newDate
      }

      def addHours(hoursToAdd: Int): SDate = {
        val newDate = new Date(millisSinceEpoch)
        newDate.setHours(newDate.getHours() + hoursToAdd)
        newDate
      }

      def millisSinceEpoch: Long = date.getTime().toLong

    }

    def apply(milliDate: MilliDate): SDate = new Date(milliDate.millisSinceEpoch)
    def apply(y: Int, m: Int, d: Int, h: Int = 0, mm: Int = 0): SDate = new Date(y, m - 1, d, h, mm)
    def today(): SDate = {
      val d = new Date()
      d.setHours(0)
      d.setMinutes(0)
      d.setMilliseconds(0)
      JSSDate(d)
    }
    def now(): SDate = {
      val d = new Date()
      JSSDate(d)
    }
  }

}

case class Shift(name: String, startDt: MilliDate, endDt: MilliDate, numberOfStaff: Int) {
  def toCsv = {
    val startDate: SDate = SDate(startDt)
    val endDate: SDate = SDate(endDt)
    val startDateString = f"${startDate.getDate}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
    val startTimeString = f"${startDate.getHours}%02d:${startDate.getMinutes}%02d"
    val endTimeString = f"${endDate.getHours}%02d:${endDate.getMinutes}%02d"

    s"$name,$startDateString,$startTimeString,$endTimeString,$numberOfStaff"
  }
}

object Shift {

  import JSDateConversions._

  def apply(name: String, startDate: String, startTime: String, endTime: String, numberOfStaff: String = "1"): Try[Shift] = {
    val staffDeltaTry = Try(numberOfStaff.toInt)
    val ymd = startDate.split("/").toVector

    val tryDMY: Try[(Int, Int, Int)] = Try((ymd(0).toInt, ymd(1).toInt, ymd(2).toInt + 2000))

    for {
      dmy <- tryDMY
      (d, m, y) = dmy

      startDtTry: Try[SDate] = parseTimeWithStartTime(startTime, d, m, y)
      endDtTry: Try[SDate] = parseTimeWithStartTime(endTime, d, m, y)
      startDt <- startDtTry
      endDt <- endDtTry
      staffDelta: Int <- staffDeltaTry
    } yield {
      Shift(name, startDt, adjustEndDateIfEndTimeIsBeforeStartTime(d, m, y, startDt, endDt), staffDelta)
    }
  }

  private def adjustEndDateIfEndTimeIsBeforeStartTime(d: Int, m: Int, y: Int, startDt: SDate, endDt: SDate): SDate = {
    if (endDt.millisSinceEpoch < startDt.millisSinceEpoch) {
      SDate(y, m, d, endDt.getHours(), endDt.getMinutes()).addDays(1)
    }
    else {
      endDt
    }
  }

  private def parseTimeWithStartTime(startTime: String, d: Int, m: Int, y: Int): Try[SDate] = {
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
  val parsedShifts: Array[Try[Shift]] = lines.map(l => l.split(","))
    .filter(parts => parts.length == 4 || parts.length == 5)
    .map(pl => pl match {
      case Array(description, startDay, startTime, endTime) =>
        Shift(description, startDay, startTime, endTime)
      case Array(description, startDay, startTime, endTime, staffNumberDelta) =>
        Shift(description, startDay, startTime, endTime, staffNumberDelta)
    })
}

case class ShiftService(shifts: Seq[Shift]) {
  def staffAt(date: MilliDate): Int = shifts.filter(shift =>
    (shift.startDt <= date && date <= shift.endDt)).map(_.numberOfStaff).sum
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

  def groupPeopleByShiftTimes(shifts: Seq[Shift]) = {
    shifts.groupBy(shift => (shift.startDt, shift.endDt, shift.name))
      .map { case ((startDt, endDt, name), shifts) => {
        Shift(name, startDt, endDt, shifts.map(_.numberOfStaff).sum)
      }
      }
  }
}

object StaffMovements {

  def shiftsToMovements(shifts: Seq[Shift]) = {
    shifts.flatMap(shift =>
      StaffMovement(shift.name + " start", time = shift.startDt, shift.numberOfStaff) ::
        StaffMovement(shift.name + " end", time = shift.endDt, -shift.numberOfStaff) :: Nil
    ).sortBy(_.time)
  }

  case class StaffMovement(reason: String, time: MilliDate, delta: Int, queue: Option[QueueName] = None) {
    def toCsv = {
      val startDate: SDate = SDate(time)
      val startDateString = f"${startDate.getDate}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
      val startTimeString = f"${startDate.getHours}%02d:${startDate.getMinutes}%02d"

      s"$reason,$startDateString,$startTimeString,$delta"
    }
  }

  def adjustmentsAt(movements: Seq[StaffMovement])(dateTime: MilliDate) = movements.takeWhile(_.time <= dateTime).map(_.delta).sum

  def staffAt(shiftService: ShiftService)(movements: Seq[StaffMovement])(dateTime: MilliDate) = {
    val baseStaff = shiftService.staffAt(dateTime)
    baseStaff + adjustmentsAt(movements)(dateTime)
  }
}

case class MovementsShiftService(shifts: List[Shift]) {
  val movements = StaffMovements.shiftsToMovements(shifts).groupBy(_.time).map(m =>
    StaffMovement(m._1.toString(), m._1, m._2.map(_.delta).sum, None)).toList
  println(s"Movements are: ${movements.mkString("\n")}")

  def staffAt(date: MilliDate): Int = StaffMovements.adjustmentsAt(movements)(date)
}
