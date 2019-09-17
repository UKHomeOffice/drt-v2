package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}

import scala.collection.SortedMap

case class QueueSummary(pax: Double, deskRecs: Int, waitTime: Int, actDesks: Option[Int], actWaitTime: Option[Int]) {
  lazy val toCsv: String = s"${Math.round(pax)},$waitTime,$deskRecs,${actWaitTime.getOrElse("")},${actDesks.getOrElse("")}"
}

case class StaffSummary(available: Int, misc: Int, moves: Int, recommended: Int) {
  lazy val toCsv: String = s"$misc,$moves,$available,$recommended"
}

case class TerminalSummary(start: SDateLike, queueSummaries: Seq[QueueSummary], staffSummary: StaffSummary) {
  lazy val toCsv = s"${start.toISODateOnly},${start.toHoursAndMinutes()},${queueSummaries.map(_.toCsv).mkString(",")},${staffSummary.toCsv}"
}


object Summaries {
  def optionalMax(optionalInts: Seq[Option[Int]]): Option[Int] = {
    val ints = optionalInts.flatten
    if (ints.isEmpty) None else Option(ints.max)
  }

  def minutesForPeriod[A, B](startMillis: MillisSinceEpoch, endMillis: MillisSinceEpoch, atTime: MillisSinceEpoch => A, data: SortedMap[A, B]): SortedMap[A, B] =
    data.range(atTime(startMillis), atTime(endMillis))

  def terminalSummaryForPeriod(terminalCms: SortedMap[TQM, CrunchMinute], terminalSms: SortedMap[TM, StaffMinute], queues: Seq[String], summaryStart: SDateLike, summaryPeriodMinutes: Int): TerminalSummary = {
    val queueSummaries = Summaries.queueSummariesForPeriod(terminalCms, queues, summaryStart, summaryPeriodMinutes)
    val smResult = Summaries.staffSummaryForPeriod(terminalSms, queueSummaries, summaryStart, summaryPeriodMinutes)

    TerminalSummary(start = summaryStart, queueSummaries = queueSummaries, staffSummary = smResult)
  }

  def queueSummariesForPeriod(terminalCms: SortedMap[TQM, CrunchMinute], queues: Seq[String], summaryStart: SDateLike, summaryMinutes: Int): Seq[QueueSummary] = {
    val startMillis = summaryStart.millisSinceEpoch
    val endMillis = summaryStart.addMinutes(summaryMinutes).millisSinceEpoch

    val byQueue = minutesForPeriod(startMillis, endMillis, TQM.atTime, terminalCms)
      .groupBy { case (tqm, _) => tqm.queueName }

    queues.map { queue =>
      byQueue.get(queue) match {
        case None => QueueSummary(0d, 0, 0, None, None)
        case Some(queueMins) =>
          val (pax, desks, waits, actDesks, actWaits) = queueMins.foldLeft((List[Double](), List[Int](), List[Int](), List[Option[Int]](), List[Option[Int]]())) {
            case ((sp, sd, sw, sad, saw), (_, CrunchMinute(_, _, _, p, _, d, w, _, _, ad, aw, _))) =>
              (p :: sp, d :: sd, w :: sw, ad :: sad, aw :: saw)
          }
          QueueSummary(pax.sum, desks.max, waits.max, optionalMax(actDesks), optionalMax(actWaits))
      }
    }
  }

  def staffSummaryForPeriod(terminalSms: SortedMap[TM, StaffMinute], queueSummaries: Seq[QueueSummary], summaryStart: SDateLike, summaryMinutes: Int): StaffSummary = {
    val startMillis = summaryStart.millisSinceEpoch
    val endMillis = summaryStart.addMinutes(summaryMinutes).millisSinceEpoch

    val minutes = minutesForPeriod(startMillis, endMillis, TM.atTime, terminalSms)

    val (fixed, moves, avail) = minutes.foldLeft((List[Int](), List[Int](), List[Int]())) {
      case ((fp, mm, av), (_, StaffMinute(_, _, s, f, m, _))) =>
        (f :: fp, m :: mm, (s + m) :: av)
    }
    val totalMisc = fixed.max
    val totalMoves = moves.min
    val totalRec = queueSummaries.map(_.deskRecs).sum + totalMisc
    StaffSummary(avail.max, totalMisc, totalMoves, totalRec)
  }
}
