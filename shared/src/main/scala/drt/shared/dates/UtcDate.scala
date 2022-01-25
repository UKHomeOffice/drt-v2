//package drt.shared.dates
//
//case class UtcDate(year: Int, month: Int, day: Int) extends DateLike {
//  override val timeZone: String = "UTC"
//}
//
//case object UtcDate {
//  def parse: String => Option[UtcDate] = DateLike.parse((y: Int, m: Int, d: Int) => UtcDate(y, m, d))
//}
