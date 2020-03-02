package services.exports.summaries.queues

import drt.shared.SDateLike

case class QueuesSummary(start: SDateLike, queueSummaries: Seq[QueueSummaryLike], staffSummary: StaffSummaryLike) {
  private val dateAndTimeCells = List(start.toISODateOnly, start.toHoursAndMinutes())
  private val queueCells: Seq[String] = queueSummaries.map(_.toCsv)
  private val staffCells: String = staffSummary.toCsv
  lazy val toCsv: String = (dateAndTimeCells ++ queueCells :+ staffCells).mkString(",")
}
