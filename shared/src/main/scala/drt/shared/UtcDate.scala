package drt.shared

import scala.util.{Success, Try}

case class UtcDate(year: Int, month: Int, day: Int) {
  def toISOString = f"$year-$month%02d-$day%02d"

  override def toString: String = toISOString
}

case object UtcDate {
  def parse(dateString: String): Option[UtcDate] = Try(
    dateString
      .split("-")
      .take(3)
      .toList
      .map(_.toInt)
  ) match {
    case Success(year :: month :: day :: tail) =>
      Option(UtcDate(year.toInt, month.toInt, day.toInt))
    case _ => None
  }
}
