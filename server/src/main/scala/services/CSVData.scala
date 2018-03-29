package services

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.joda.time.DateTimeZone
import org.slf4j.LoggerFactory
import services.graphstages.Crunch
import services.graphstages.Crunch.europeLondonTimeZone

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future


object CSVData {

  val log = LoggerFactory.getLogger(getClass)
  val lineEnding = "\n"


  def forecastHeadlineToCSV(headlines: ForecastHeadlineFigures, queueOrder: List[String]): String = {
    val headings = "," + headlines.queueDayHeadlines.map(_.day).toList.sorted.map(
      day => {
        val localDate = SDate(day, europeLondonTimeZone)
        f"${localDate.getDate()}%02d/${localDate.getMonth()}%02d"
      }
    ).mkString(",")
    val queues: String = queueOrder.flatMap(
      q => {
        headlines.queueDayHeadlines.groupBy(_.queue).get(q).map(
          qhls => (s"${Queues.queueDisplayNames.getOrElse(q, q)}" ::
            qhls
              .toList
              .sortBy(_.day)
              .map(qhl => qhl.paxNos.toString)
            ).mkString(",")
        )
      }
    ).mkString(lineEnding)

    val totalPax = "Total Pax," + headlines
      .queueDayHeadlines
      .groupBy(_.day)
      .toList.sortBy(_._1)
      .map(hl => hl._2.toList.map(_.paxNos).sum)
      .mkString(",")

    val totalWL = "Total Workload," + headlines
      .queueDayHeadlines
      .groupBy(_.day)
      .toList.sortBy(_._1)
      .map(hl => hl._2.toList.map(_.workload).sum)
      .mkString(",")

    List(headings, totalPax, queues, totalWL).mkString(lineEnding)
  }

  def forecastPeriodToCsv(forecastPeriod: ForecastPeriod): String = {
    val sortedDays: Seq[(MillisSinceEpoch, Seq[ForecastTimeSlot])] = forecastPeriod.days.toList.sortBy(_._1)
    log.info(s"Forecast CSV Export: Days in period: ${sortedDays.length}")
    val byTimeSlot: Iterable[Iterable[ForecastTimeSlot]] = sortedDays.filter {
      case (millis, forecastTimeSlots) if forecastTimeSlots.length == 96 =>
        log.info(s"Forecast CSV Export: day ${SDate(millis, europeLondonTimeZone).toLocalDateTimeString()}" +
          s" first:${SDate(forecastTimeSlots.head.startMillis, europeLondonTimeZone).toLocalDateTimeString()}" +
          s" last:${SDate(forecastTimeSlots.last.startMillis, europeLondonTimeZone).toLocalDateTimeString()}")
        true
      case (millis, forecastTimeSlots) =>
        log.error(s"Forecast CSV Export: error for ${SDate(millis, europeLondonTimeZone).toLocalDateTimeString()} got ${forecastTimeSlots.length} days")
        false
    }.transpose(_._2.take(96))

    val headings = "," + sortedDays.map {
      case (day, _) =>
        val localDate = SDate(day, europeLondonTimeZone)
        val date = localDate.getDate()
        val month = localDate.getMonth()
        val columnPrefix = f"$date%02d/$month%02d - "
        columnPrefix + "available," + columnPrefix + "required," + columnPrefix + "difference"
    }.mkString(",")

    val data = byTimeSlot.map(row => {
      val localHoursMinutes = SDate(row.head.startMillis, europeLondonTimeZone).toHoursAndMinutes()
      s"$localHoursMinutes" + "," +
        row.map(col => {
          s"${col.available},${col.required},${col.available - col.required}"
        }).mkString(",")
    }).mkString(lineEnding)

    List(headings, data).mkString(lineEnding)
  }

  def terminalCrunchMinutesToCsvDataHeadings(queues: Seq[QueueName]): String = {
    val colHeadings = List("Pax", "Wait", "Desks req", "Act. wait time", "Act. desks")
    val eGatesHeadings = List("Pax", "Wait", "Staff req", "Act. wait time", "Act. desks")
    val relevantQueues = queues
      .filterNot(_ == Queues.Transfer)
    val queueHeadings = relevantQueues
      .flatMap(qn => List.fill(colHeadings.length)(Queues.exportQueueDisplayNames.getOrElse(qn, qn))).mkString(",")
    val headingsLine1 = "Date,," + queueHeadings +
      ",Misc,PCP Staff,PCP Staff"
    val headingsLine2 = ",Start," + relevantQueues.flatMap(q => {
      if (q == Queues.EGate) eGatesHeadings else colHeadings
    }).mkString(",") +
      ",Staff req,Avail,Req"


    headingsLine1 + lineEnding + headingsLine2
  }

  def terminalCrunchMinutesToCsvDataWithHeadings(cms: Set[CrunchMinute], staffMinutes: Set[StaffMinute], terminalName: TerminalName, queues: Seq[QueueName]): String =

    terminalCrunchMinutesToCsvDataHeadings(queues) + lineEnding + terminalCrunchMinutesToCsvData(cms, staffMinutes, terminalName, queues)


  def terminalCrunchMinutesToCsvData(cms: Set[CrunchMinute], staffMinutes: Set[StaffMinute], terminalName: TerminalName, queues: Seq[QueueName]): String = {

    val crunchMilliMinutes = CrunchApi.terminalMinutesByMinute(cms, terminalName)
    val staffMilliMinutes = CrunchApi
      .terminalMinutesByMinute(staffMinutes, terminalName)
      .map { case (minute, sms) => (minute, sms.head) }

    val crunchMinutes = CrunchApi.groupCrunchMinutesByX(15)(crunchMilliMinutes, terminalName, queues.toList)
      .collect {
        case (min, cm) =>
          val queueMinutes = cm.groupBy(_.queueName)
          val terminalData = queues.flatMap(qn => {
            queueMinutes.getOrElse(qn, Nil).toList.flatMap(cm => {
              List(
                Math.round(cm.paxLoad).toString,
                Math.round(cm.waitTime).toString,
                cm.deskRec.toString,
                cm.actWait.getOrElse(""),
                cm.actDesks.getOrElse("")
              )
            })
          })

          (min, terminalData)
      }
      .toList
      .sortBy(m => m._1)
      .map {
        case (minute, queueData) =>
          val staffBy15Minutes: Map[MillisSinceEpoch, StaffMinute] = groupStaffMinutesByX(15)(staffMilliMinutes, terminalName).toMap
          val staffMinute = staffBy15Minutes.getOrElse(minute, StaffMinute.empty)
          val staffData: Seq[String] = List(staffMinute.fixedPoints.toString, (staffMinute.shifts - staffMinute.movements).toString)
          val reqForMinute = crunchMilliMinutes
            .toList
            .find { case (minuteMilli, _) => minuteMilli == minute }
            .map { case (_, queueCrunchMinutes) => DesksAndQueues.totalRequired(staffMinute, queueCrunchMinutes) }
            .getOrElse(0)

          val hoursAndMinutes = SDate(minute).toHoursAndMinutes()
          val queueFields = queueData.mkString(",")
          val pcpFields = staffData.mkString(",")
          val dateString = SDate(minute).toISODateOnly

          dateString + "," + hoursAndMinutes + "," + queueFields + "," + pcpFields + "," + reqForMinute
      }

    crunchMinutes.mkString(lineEnding)
  }

  def flightsWithSplitsToCSVWithHeadings(flightsWithSplits: List[ApiFlightWithSplits]): String =
    flightsWithSplitsToCSVHeadings + lineEnding + flightsWithSplitsToCSV(flightsWithSplits)


  def flightsWithSplitsToCSVHeadings: String = {
    val queueNames = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrderWithFastTrack)
    val headings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Arrival,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

    headings
  }

  def flightsWithSplitsToCSV(flightsWithSplits: List[ApiFlightWithSplits]): String = {
    val queueNames = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrderWithFastTrack)

    val csvData = flightsWithSplits.sortBy(_.apiFlight.PcpTime).map(fws => {

      val flightCsvFields = List(
        fws.apiFlight.IATA,
        fws.apiFlight.ICAO,
        fws.apiFlight.Origin,
        fws.apiFlight.Gate + "/" + fws.apiFlight.Stand,
        fws.apiFlight.Status,
        fws.apiFlight.SchDT,
        fws.apiFlight.EstDT,
        fws.apiFlight.ActDT,
        fws.apiFlight.EstChoxDT,
        fws.apiFlight.ActChoxDT,
        SDate(fws.apiFlight.PcpTime).toISOString(),
        fws.apiFlight.ActPax,
        ArrivalHelper.bestPax(fws.apiFlight)
      ) ++
        queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).getOrElse(q, "")}") ++
        queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.Historical).getOrElse(q, "")}") ++
        queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.TerminalAverage).getOrElse(q, "")}")

      flightCsvFields
    }).map(_.mkString(",")).mkString(lineEnding)


    csvData
  }

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: String): Map[QueueName, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight))
      .getOrElse(Map())

  def headingsForSplitSource(queueNames: Seq[String], source: String): String = {
    queueNames
      .map(q => {
        val queueName = Queues.queueDisplayNames(q)
        s"$source $queueName"
      })
      .mkString(",")
  }

  def multiDayToSingleExport(exportDays: Seq[Future[Option[String]]]): Future[String] = {

    Future.sequence(
      exportDays.map(fd => fd.recoverWith {
        case e =>
          log.error(s"Failed to recover data for day ${e.getMessage}")
          Future(None)
      }
      )).map(_.collect {
      case Some(s) => s
    }.mkString(lineEnding))
  }
}
