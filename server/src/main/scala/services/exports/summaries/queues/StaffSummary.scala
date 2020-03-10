package services.exports.summaries.queues

sealed trait StaffSummaryLike {
  val available: Int
  val misc: Int
  val moves: Int
  val recommended: Int
  val toCsv: String
}

case object EmptyStaffSummary extends StaffSummaryLike {
  override val available: Int = 0
  override val misc: Int = 0
  override val moves: Int = 0
  override val recommended: Int = 0
  override val toCsv: String = s"0,0,0,0"
}

case class StaffSummary(available: Int, misc: Int, moves: Int, recommended: Int) extends StaffSummaryLike {
  lazy val toCsv: String = s"$misc,$moves,$available,$recommended"
}
