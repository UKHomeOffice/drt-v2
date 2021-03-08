package drt.shared.dates

import upickle.default.{ReadWriter, macroRW}

case class LocalDate(year: Int, month: Int, day: Int) extends DateLike {
  override val timeZone: String = "Europe/London"
}

case object LocalDate {

  implicit val rw: ReadWriter[LocalDate] = macroRW

  def parse: String => Option[LocalDate] = DateLike.parse((y: Int, m: Int, d: Int) => LocalDate(y, m, d))
}
