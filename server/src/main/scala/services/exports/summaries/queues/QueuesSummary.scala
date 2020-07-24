package services.exports.summaries.queues

import drt.shared.SDateLike
import services.SDate
import services.graphstages.Crunch

case class QueuesSummary(start: SDateLike, queueSummaries: Seq[QueueSummaryLike], staffSummary: StaffSummaryLike) {
  private val localStart = SDate(start.millisSinceEpoch, Crunch.europeLondonTimeZone)
  private val dateAndTimeCells = List(localStart.toISODateOnly, localStart.toHoursAndMinutes)
  private val queueCells: Seq[String] = queueSummaries.map(_.toCsv)
  private val staffCells: String = staffSummary.toCsv
  lazy val toCsv: String = (dateAndTimeCells ++ queueCells :+ staffCells).mkString(",")
}
