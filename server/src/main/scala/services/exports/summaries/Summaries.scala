package services.exports.summaries

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.{SDateLike, TM, TQM}
import services.exports.summaries.queues._

import scala.collection.SortedMap


case object GetSummaries

object Summaries {
  def optionalMax(optionalInts: Seq[Option[Int]]): Option[Int] = {
    val ints = optionalInts.flatten
    if (ints.isEmpty) None else Option(ints.max)
  }

  def minutesForPeriod[A, B](startMillis: MillisSinceEpoch,
                             endMillis: MillisSinceEpoch,
                             atTime: MillisSinceEpoch => A,
                             data: SortedMap[A, B]): SortedMap[A, B] =
    data.range(atTime(startMillis), atTime(endMillis))

  def terminalSummaryForPeriod(terminalCms: SortedMap[TQM, CrunchMinute],
                               terminalSms: SortedMap[TM, StaffMinute],
                               queues: Seq[Queue],
                               summaryStart: SDateLike,
                               summaryPeriodMinutes: Int): QueuesSummary = {
    val queueSummaries = Summaries.queueSummariesForPeriod(terminalCms, queues, summaryStart, summaryPeriodMinutes)
    val smResult = Summaries.staffSummaryForPeriod(terminalSms, queueSummaries, summaryStart, summaryPeriodMinutes)

    QueuesSummary(start = summaryStart, queueSummaries = queueSummaries, staffSummary = smResult)
  }

  def queueSummariesForPeriod(terminalCms: SortedMap[TQM, CrunchMinute],
                              queues: Seq[Queue],
                              summaryStart: SDateLike,
                              summaryMinutes: Int): Seq[QueueSummaryLike] = {
    val startMillis = summaryStart.millisSinceEpoch
    val endMillis = summaryStart.addMinutes(summaryMinutes).millisSinceEpoch

    val byQueue = minutesForPeriod(startMillis, endMillis, TQM.atTime, terminalCms)
      .groupBy { case (tqm, _) => tqm.queue }

    queues.map { queue =>
      byQueue.get(queue) match {
        case None => EmptyQueueSummary
        case Some(queueMins) if queueMins.isEmpty => EmptyQueueSummary
        case Some(queueMins) => queueSummaryFromMinutes(queueMins)
      }
    }
  }

  private def queueSummaryFromMinutes(queueMins: SortedMap[TQM, CrunchMinute]): QueueSummaryLike = {
    val (pax, desks, waits, actDesks, actWaits) = queueMins.foldLeft((List[Double](), List[Int](), List[Int](), List[Option[Int]](), List[Option[Int]]())) {
      case ((sp, sd, sw, sad, saw), (_, CrunchMinute(_, _, _, p, _, d, w, _, _, ad, aw, _))) =>
        (p :: sp, d :: sd, w :: sw, ad :: sad, aw :: saw)
    }
    QueueSummary(pax.sum, desks.max, waits.max, optionalMax(actDesks), optionalMax(actWaits))
  }

  def staffSummaryForPeriod(terminalSms: SortedMap[TM, StaffMinute],
                            queueSummaries: Seq[QueueSummaryLike],
                            summaryStart: SDateLike,
                            summaryMinutes: Int): StaffSummaryLike = {
    val startMillis = summaryStart.millisSinceEpoch
    val endMillis = summaryStart.addMinutes(summaryMinutes).millisSinceEpoch

    val minutes = minutesForPeriod(startMillis, endMillis, TM.atTime, terminalSms)

    if (minutes.isEmpty && queueSummaries.isEmpty) EmptyStaffSummary
    else staffSummaryFromMinutes(queueSummaries, minutes)
  }

  private def staffSummaryFromMinutes(queueSummaries: Seq[QueueSummaryLike],
                                      minutes: SortedMap[TM, StaffMinute]): StaffSummaryLike = {
    val (misc, moves, avail) = minutes.foldLeft((List[Int](), List[Int](), List[Int]())) {
      case ((smc, smm, sav), (_, StaffMinute(_, _, shifts, miscellaneous, movements, _))) =>
        val available = shifts + movements
        (miscellaneous :: smc, movements :: smm, available :: sav)
    }
    val totalMisc = if (misc.nonEmpty) misc.max else 0
    val maxAvail = if (avail.nonEmpty) avail.max else 0
    val minMoves = if (moves.nonEmpty) moves.min else 0
    val queueRecs = if (queueSummaries.nonEmpty) queueSummaries.map(_.deskRecs).sum else 0
    val totalRec = queueRecs + totalMisc
    StaffSummary(maxAvail, totalMisc, minMoves, totalRec)
  }
}
