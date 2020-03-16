package services.exports.summaries.queues

import drt.shared.Queues
import drt.shared.Queues.Queue
import services.exports.summaries.TerminalSummaryLike

case class TerminalQueuesSummary(queues: Seq[Queue], summaries: Iterable[QueuesSummary]) extends TerminalSummaryLike {
  override def isEmpty: Boolean = summaries.isEmpty

  import TerminalQueuesSummary._

  override lazy val toCsv: String = summaries.map(_.toCsv).mkString(lineEnding) + lineEnding

  override lazy val csvHeader: String = {
    val relevantQueues = queues
      .filterNot(_ == Queues.Transfer)
    val headingsLine1 = "Date,," + queueHeadings(relevantQueues) + ",Misc,Moves,PCP Staff,PCP Staff"
    val headingsLine2 = ",Start," + relevantQueues.flatMap(q => {
      if (q == Queues.EGate) eGatesHeadings else colHeadings
    }).mkString(",") +
      ",Staff req,Staff movements,Avail,Req"

    headingsLine1 + lineEnding + headingsLine2
  }
}

object TerminalQueuesSummary {
  val colHeadings = List("Pax", "Wait", "Desks req", "Act. wait time", "Act. desks")
  val eGatesHeadings = List("Pax", "Wait", "Staff req", "Act. wait time", "Act. desks")

  def queueHeadings(queues: Seq[Queue]): String = queues.map(queue => Queues.queueDisplayNames.getOrElse(queue, queue.toString))
    .flatMap(qn => List.fill(colHeadings.length)(Queues.exportQueueDisplayNames.getOrElse(Queue(qn), qn))).mkString(",")
}
