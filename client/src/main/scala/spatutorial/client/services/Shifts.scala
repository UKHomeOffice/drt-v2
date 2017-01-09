package spatutorial.client.services

import spatutorial.client.services.StaffMovements.StaffMovement
import spatutorial.shared.FlightsApi._
import spatutorial.shared.MilliDate

import scala.collection.immutable.Seq
import scala.scalajs.js.Date


object JSDateConversions {
  implicit def jsDateToMillis(jsDate: Date): Long = jsDate.getTime().toLong

  implicit def jsDateToMilliDate(jsDate: Date): MilliDate = MilliDate(jsDateToMillis(jsDate))

  implicit def longToMilliDate(millis: Long): MilliDate = MilliDate(millis)

  object SDate {
    def apply(y: Int, m: Int, d: Int, h: Int, mm: Int) = new Date(y, m, d, h, mm)
  }

}

case class Shift(name: String, startDt: MilliDate, endDt: MilliDate, numberOfStaff: Int)

object Shift {

  import JSDateConversions._

  def apply(name: String, startDate: String, startTime: String, endTime: String, numberOfStaff: Int = 1): Shift = {
    val ymd = startDate.split("/").toVector

    val (d, m, y) = (ymd(0).toInt, ymd(1).toInt - 1, ymd(2).toInt + 2000)

    val startT = startTime.split(":").toVector
    val (startHour, startMinute) = (startT(0).toInt, startT(1).toInt)
    val startDt = SDate(y, m, d, startHour, startMinute)

    val endT = endTime.split(":").toVector
    val (endHour, endMinute) = (endT(0).toInt, endT(1).toInt)
    val endDtTry = SDate(y, m, d, endHour, endMinute)
    val endDt = if (endDtTry.millisSinceEpoch < startDt.millisSinceEpoch)
      SDate(y, m, d + 1, endHour, endMinute)
    else
      endDtTry
    Shift(name, startDt, endDt, numberOfStaff)
  }
}

case class Shifts(rawShifts: String) {
  val lines = rawShifts.split("\n")
  val parsedShifts = lines.map(l => l.split(","))
    .filter(parts => parts.length == 4 || parts.length == 5)
    .map(pl => pl.length match {
      case 4 => Shift(pl(0), pl(1), pl(2), pl(3))
      case 5 => Shift(pl(0), pl(1), pl(2), pl(3), pl(4).toInt)
    })
}

case class ShiftService(shifts: List[Shift]) {
  def staffAt(date: MilliDate): Int = shifts.filter(shift =>
    (shift.startDt <= date && date <= shift.endDt)).map(_.numberOfStaff).sum
}

object ShiftService {
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

  case class StaffMovement(reason: String, time: MilliDate, delta: Int, queue: Option[QueueName] = None)

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