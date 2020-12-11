package drt.shared

import scala.util.{Success, Try}

trait DateLike extends Ordered[DateLike] {
  val timeZone: String

  val year: Int
  val month: Int
  val day: Int
  val toISOString: String = f"$year-$month%02d-$day%02d"

  override def toString: String = toISOString

  override def compare(that: DateLike): Int =
    if (toISOString < that.toISOString) -1
    else if (toISOString > that.toISOString) 1
    else 0
}

object DateLike {
  def parse[A <: DateLike](toDateLike: (Int, Int, Int) => A): String => Option[A] =
    (dateString: String) => Try(
    dateString
      .split("-")
      .take(3)
      .toList
      .map(_.toInt)
  ) match {
    case Success(year :: month :: day :: _) =>
      Option(toDateLike(year, month, day))
    case _ => None
  }
}

object DateLikeOrdering extends Ordering[DateLike] {
  override def compare(x: DateLike, y: DateLike): Int = x.compare(y)
}

case class UtcDate(year: Int, month: Int, day: Int) extends DateLike {
  override val timeZone: String = "UTC"
}

case object UtcDate {
  def parse: String => Option[UtcDate] = DateLike.parse((y: Int, m: Int, d: Int) => UtcDate(y, m, d))
}
