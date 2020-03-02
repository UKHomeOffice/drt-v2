//package drt.shared
//
//import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
//import drt.shared.Queues.Queue
//import drt.shared.SplitRatiosNs.SplitSource
//import drt.shared.splits.ApiSplitsToSplitRatio
//
//import scala.collection.SortedMap
//
//sealed trait QueueSummaryLike {
//  val pax: Double
//  val deskRecs: Int
//  val waitTime: Int
//  val actDesks: Option[Int]
//  val actWaitTime: Option[Int]
//  val toCsv: String
//}
//
//case object EmptyQueueSummary extends QueueSummaryLike {
//  override val pax: Double = 0d
//  override val deskRecs: Int = 0
//  override val waitTime: Int = 0
//  override val actDesks: Option[Int] = None
//  override val actWaitTime: Option[Int] = None
//  override val toCsv: String = "0,0,0,,"
//}
//
//case class QueueSummary(pax: Double,
//                        deskRecs: Int,
//                        waitTime: Int,
//                        actDesks: Option[Int],
//                        actWaitTime: Option[Int]) extends QueueSummaryLike {
//  lazy val toCsv: String = s"${Math.round(pax)},$waitTime,$deskRecs,${actWaitTime.getOrElse("")},${actDesks.getOrElse("")}"
//}
//
//sealed trait StaffSummaryLike {
//  val available: Int
//  val misc: Int
//  val moves: Int
//  val recommended: Int
//  val toCsv: String
//}
//
//case object EmptyStaffSummary extends StaffSummaryLike {
//  override val available: Int = 0
//  override val misc: Int = 0
//  override val moves: Int = 0
//  override val recommended: Int = 0
//  override val toCsv: String = s"0,0,0,0"
//}
//
//case class StaffSummary(available: Int, misc: Int, moves: Int, recommended: Int) extends StaffSummaryLike {
//  lazy val toCsv: String = s"$misc,$moves,$available,$recommended"
//}
//
//case class QueuesSummary(start: SDateLike, queueSummaries: Seq[QueueSummaryLike], staffSummary: StaffSummaryLike) {
//  private val dateAndTimeCells = List(start.toISODateOnly, start.toHoursAndMinutes())
//  private val queueCells: Seq[String] = queueSummaries.map(_.toCsv)
//  private val staffCells: String = staffSummary.toCsv
//  lazy val toCsv: String = (dateAndTimeCells ++ queueCells :+ staffCells).mkString(",")
//}
//
//sealed trait TerminalSummaryLike {
//  val lineEnding = "\r\n"
//
//  def toCsv: String
//
//  def csvHeader: String
//
//  def toCsvWithHeader: String = csvHeader + lineEnding + toCsv
//}
//
//sealed trait TerminalFlightsSummaryLike extends TerminalSummaryLike {
//  def flights: Seq[ApiFlightWithSplits]
//
//  def millisToDateOnly: MillisSinceEpoch => String
//
//  def millisToHoursAndMinutes: MillisSinceEpoch => String
//
//  val queueNames: Seq[Queue] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrder)
//
//  def csvHeader: String
//
//  def headingsForSplitSource(queueNames: Seq[Queue], source: String): String = queueNames
//    .map(q => {
//      val queueName = Queues.queueDisplayNames(q)
//      s"$source $queueName"
//    })
//    .mkString(",")
//
//  def flightToCsvRow(queueNames: Seq[Queue], fws: ApiFlightWithSplits): List[Any] = {
//    List(
//      fws.apiFlight.flightCode,
//      fws.apiFlight.flightCode,
//      fws.apiFlight.Origin,
//      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
//      fws.apiFlight.Status.description,
//      millisToDateOnly(fws.apiFlight.Scheduled),
//      millisToHoursAndMinutes(fws.apiFlight.Scheduled),
//      fws.apiFlight.Estimated.map(millisToHoursAndMinutes(_)).getOrElse(""),
//      fws.apiFlight.Actual.map(millisToHoursAndMinutes(_)).getOrElse(""),
//      fws.apiFlight.EstimatedChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
//      fws.apiFlight.ActualChox.map(millisToHoursAndMinutes(_)).getOrElse(""),
//      fws.apiFlight.PcpTime.map(millisToHoursAndMinutes(_)).getOrElse(""),
//      fws.apiFlight.ActPax.getOrElse(0),
//      ArrivalHelper.bestPax(fws.apiFlight)
//      ) ++
//      queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).getOrElse(q, "")}") ++
//      queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.Historical).getOrElse(q, "")}") ++
//      queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.TerminalAverage).getOrElse(q, "")}")
//  }
//
//  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: SplitSource): Map[Queue, Int] =
//    fws
//      .splits
//      .find(_.source == splitSource)
//      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight))
//      .getOrElse(Map())
//
//  def asCSV(csvData: Iterable[List[Any]]): String = csvData.map(_.mkString(",")).mkString(lineEnding)
//}
//
//case class TerminalFlightsSummary(flights: Seq[ApiFlightWithSplits],
//                                  millisToDateOnly: MillisSinceEpoch => String,
//                                  millisToHoursAndMinutes: MillisSinceEpoch => String) extends TerminalFlightsSummaryLike {
//  override lazy val csvHeader: String =
//    "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
//      headingsForSplitSource(queueNames, "API") + "," +
//      headingsForSplitSource(queueNames, "Historical") + "," +
//      headingsForSplitSource(queueNames, "Terminal Average")
//
//  override def toCsv: String = {
//    val csvData = flights.sortBy(_.apiFlight.PcpTime).map(fws => {
//      flightToCsvRow(queueNames, fws)
//    })
//    asCSV(csvData)
//  }
//}
//
//case class TerminalFlightsWithActualApiSummary(flights: Seq[ApiFlightWithSplits],
//                                               millisToDateOnly: MillisSinceEpoch => String,
//                                               millisToHoursAndMinutes: MillisSinceEpoch => String) extends TerminalFlightsSummaryLike {
//  override lazy val csvHeader: String = standardCsvHeader + "," + actualAPIHeadings.mkString(",")
//
//  override def toCsv: String =  {
//    val csvData = flights.sortBy(_.apiFlight.PcpTime).map(fws => {
//      flightToCsvRow(queueNames, fws) ::: actualAPISplitsForFlightInHeadingOrder(fws, actualAPIHeadings).toList
//    })
//    asCSV(csvData)
//  }
//
//  lazy val standardCsvHeader: String =
//    "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
//      headingsForSplitSource(queueNames, "API") + "," +
//      headingsForSplitSource(queueNames, "Historical") + "," +
//      headingsForSplitSource(queueNames, "Terminal Average")
//
//  lazy val actualAPIHeadings: Seq[String] =
//    flights.flatMap(f => actualAPISplitsAndHeadingsFromFlight(f).map(_._1)).distinct.sorted
//
//  def actualAPISplitsAndHeadingsFromFlight(flightWithSplits: ApiFlightWithSplits): Set[(String, Double)] = flightWithSplits
//    .splits
//    .collect {
//      case s: Splits if s.source == SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages =>
//        s.splits.map(s => {
//          val ptaq = PaxTypeAndQueue(s.passengerType, s.queueType)
//          (s"API Actual - ${PaxTypesAndQueues.displayName(ptaq)}", s.paxCount)
//        })
//    }
//    .flatten
//
//  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
//    headings.map(h => actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
//      .map(n => Math.round(n).toDouble)
//
//}
//
//case class TerminalQueuesSummary(queues: Seq[Queue], summaries: Iterable[QueuesSummary]) extends TerminalSummaryLike {
//  override lazy val toCsv: String = summaries.map(_.toCsv).mkString(lineEnding)
//
//  override lazy val csvHeader: String = {
//    val colHeadings = List("Pax", "Wait", "Desks req", "Act. wait time", "Act. desks")
//    val eGatesHeadings = List("Pax", "Wait", "Staff req", "Act. wait time", "Act. desks")
//    val relevantQueues = queues
//      .filterNot(_ == Queues.Transfer)
//    val queueHeadings = relevantQueues.map(queue => Queues.queueDisplayNames.getOrElse(queue, queue.toString))
//      .flatMap(qn => List.fill(colHeadings.length)(Queues.exportQueueDisplayNames.getOrElse(Queue(qn), qn))).mkString(",")
//    val headingsLine1 = "Date,," + queueHeadings +
//      ",Misc,Moves,PCP Staff,PCP Staff"
//    val headingsLine2 = ",Start," + relevantQueues.flatMap(q => {
//      if (q == Queues.EGate) eGatesHeadings else colHeadings
//    }).mkString(",") +
//      ",Staff req,Staff movements,Avail,Req"
//
//    headingsLine1 + lineEnding + headingsLine2
//  }
//}
//
//case object GetSummaries
//
//object Summaries {
//  def optionalMax(optionalInts: Seq[Option[Int]]): Option[Int] = {
//    val ints = optionalInts.flatten
//    if (ints.isEmpty) None else Option(ints.max)
//  }
//
//  def minutesForPeriod[A, B](startMillis: MillisSinceEpoch,
//                             endMillis: MillisSinceEpoch,
//                             atTime: MillisSinceEpoch => A,
//                             data: SortedMap[A, B]): SortedMap[A, B] =
//    data.range(atTime(startMillis), atTime(endMillis))
//
//  def terminalSummaryForPeriod(terminalCms: SortedMap[TQM, CrunchMinute],
//                               terminalSms: SortedMap[TM, StaffMinute],
//                               queues: Seq[Queue],
//                               summaryStart: SDateLike,
//                               summaryPeriodMinutes: Int): QueuesSummary = {
//    val queueSummaries = Summaries.queueSummariesForPeriod(terminalCms, queues, summaryStart, summaryPeriodMinutes)
//    val smResult = Summaries.staffSummaryForPeriod(terminalSms, queueSummaries, summaryStart, summaryPeriodMinutes)
//
//    QueuesSummary(start = summaryStart, queueSummaries = queueSummaries, staffSummary = smResult)
//  }
//
//  def queueSummariesForPeriod(terminalCms: SortedMap[TQM, CrunchMinute],
//                              queues: Seq[Queue],
//                              summaryStart: SDateLike,
//                              summaryMinutes: Int): Seq[QueueSummaryLike] = {
//    val startMillis = summaryStart.millisSinceEpoch
//    val endMillis = summaryStart.addMinutes(summaryMinutes).millisSinceEpoch
//
//    val byQueue = minutesForPeriod(startMillis, endMillis, TQM.atTime, terminalCms)
//      .groupBy { case (tqm, _) => tqm.queue }
//
//    queues.map { queue =>
//      byQueue.get(queue) match {
//        case None => EmptyQueueSummary
//        case Some(queueMins) if queueMins.isEmpty => EmptyQueueSummary
//        case Some(queueMins) => queueSummaryFromMinutes(queueMins)
//      }
//    }
//  }
//
//  private def queueSummaryFromMinutes(queueMins: SortedMap[TQM, CrunchMinute]): QueueSummaryLike = {
//    val (pax, desks, waits, actDesks, actWaits) = queueMins.foldLeft((List[Double](), List[Int](), List[Int](), List[Option[Int]](), List[Option[Int]]())) {
//      case ((sp, sd, sw, sad, saw), (_, CrunchMinute(_, _, _, p, _, d, w, _, _, ad, aw, _))) =>
//        (p :: sp, d :: sd, w :: sw, ad :: sad, aw :: saw)
//    }
//    QueueSummary(pax.sum, desks.max, waits.max, optionalMax(actDesks), optionalMax(actWaits))
//  }
//
//  def staffSummaryForPeriod(terminalSms: SortedMap[TM, StaffMinute],
//                            queueSummaries: Seq[QueueSummaryLike],
//                            summaryStart: SDateLike,
//                            summaryMinutes: Int): StaffSummaryLike = {
//    val startMillis = summaryStart.millisSinceEpoch
//    val endMillis = summaryStart.addMinutes(summaryMinutes).millisSinceEpoch
//
//    val minutes = minutesForPeriod(startMillis, endMillis, TM.atTime, terminalSms)
//
//    if (minutes.isEmpty && queueSummaries.isEmpty) EmptyStaffSummary
//    else staffSummaryFromMinutes(queueSummaries, minutes)
//  }
//
//  private def staffSummaryFromMinutes(queueSummaries: Seq[QueueSummaryLike],
//                                      minutes: SortedMap[TM, StaffMinute]): StaffSummaryLike = {
//    val (misc, moves, avail) = minutes.foldLeft((List[Int](), List[Int](), List[Int]())) {
//      case ((smc, smm, sav), (_, StaffMinute(_, _, shifts, miscellaneous, movements, _))) =>
//        val available = shifts + movements
//        (miscellaneous :: smc, movements :: smm, available :: sav)
//    }
//    val totalMisc = if (misc.nonEmpty) misc.max else 0
//    val maxAvail = if (avail.nonEmpty) avail.max else 0
//    val minMoves = if (moves.nonEmpty) moves.min else 0
//    val queueRecs = if (queueSummaries.nonEmpty) queueSummaries.map(_.deskRecs).sum else 0
//    val totalRec = queueRecs + totalMisc
//    StaffSummary(maxAvail, totalMisc, minMoves, totalRec)
//  }
//}
