package drt.shared.dates

case class LocalDate(year: Int, month: Int, day: Int) extends DateLike {
  override val timeZone: String = "Europe/London"
}

case object LocalDate {
  def parse: String => Option[LocalDate] = DateLike.parse((y: Int, m: Int, d: Int) => LocalDate(y, m, d))
}
