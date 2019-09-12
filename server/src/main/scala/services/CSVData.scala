package services

import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import drt.shared.splits.ApiSplitsToSplitRatio
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.Try


object CSVData {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val lineEnding = "\n"

  def forecastHeadlineToCSV(headlines: ForecastHeadlineFigures, queueOrder: List[String]): String = {
    val headings = "," + headlines.queueDayHeadlines.map(_.day).toSet.toList.sorted.map(
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

  def forecastPeriodToCsv(forecastPeriod: ForecastPeriod): String =
    makeDayHeadingsForPlanningExport(forecastDaysInPeriod(forecastPeriod)) +
      lineEnding +
      periodTimeslotsToCSVString(
        Forecast.timeSlotStartTimes(forecastPeriod, millisToHoursAndMinutesString),
        Forecast.periodByTimeSlotAcrossDays(forecastPeriod)
      )

  def millisToHoursAndMinutesString: MillisSinceEpoch => String =
    (millis: MillisSinceEpoch) => SDate(millis, europeLondonTimeZone).toHoursAndMinutes()

  def periodTimeslotsToCSVString(timeSlotStarts: Seq[String], byTimeSlot: List[List[Option[ForecastTimeSlot]]]): String = {
    byTimeSlot.zip(timeSlotStarts).map {
      case (row, startTime) =>
        s"$startTime" + "," +
          row.map(forecastTimeSlotOptionToCSV).mkString(",")
    }.mkString(lineEnding)
  }

  def forecastTimeSlotOptionToCSV(rowOption: Option[ForecastTimeSlot]): String = rowOption match {
    case Some(col) =>
      s"${col.available},${col.required},${col.available - col.required}"
    case None =>
      s",,"
  }

  def makeDayHeadingsForPlanningExport(daysInPeriod: Seq[MillisSinceEpoch]): QueueName = {
    "," + daysInPeriod.map(day => {
      val localDate = SDate(day, europeLondonTimeZone)
      val date = localDate.getDate()
      val month = localDate.getMonth()
      val columnPrefix = f"$date%02d/$month%02d - "
      columnPrefix + "available," + columnPrefix + "required," + columnPrefix + "difference"
    }).mkString(",")
  }

  def forecastDaysInPeriod(forecastPeriod: ForecastPeriod): Seq[MillisSinceEpoch] = forecastPeriod.days.toList.map(_._1).sorted

  def terminalCrunchMinutesToCsvDataHeadings(queues: Seq[QueueName]): String = {
    val colHeadings = List("Pax", "Wait", "Desks req", "Act. wait time", "Act. desks")
    val eGatesHeadings = List("Pax", "Wait", "Staff req", "Act. wait time", "Act. desks")
    val relevantQueues = queues
      .filterNot(_ == Queues.Transfer)
    val queueHeadings = relevantQueues.map(queue => Queues.queueDisplayNames.getOrElse(queue, queue))
      .flatMap(qn => List.fill(colHeadings.length)(Queues.exportQueueDisplayNames.getOrElse(qn, qn))).mkString(",")
    val headingsLine1 = "Date,," + queueHeadings +
      ",Misc,Moves,PCP Staff,PCP Staff"
    val headingsLine2 = ",Start," + relevantQueues.flatMap(q => {
      if (q == Queues.EGate) eGatesHeadings else colHeadings
    }).mkString(",") +
      ",Staff req,Staff movements,Avail,Req"

    headingsLine1 + lineEnding + headingsLine2
  }

  def terminalCrunchMinutesToCsvDataWithHeadings(cms: List[CrunchMinute], staffMinutes: List[StaffMinute], terminalName: TerminalName, queues: Seq[QueueName]): String =
    terminalCrunchMinutesToCsvDataHeadings(queues) + lineEnding + terminalCrunchMinutesToCsvData(cms, staffMinutes, terminalName, queues)


  def terminalCrunchMinutesToCsvData(cms: List[CrunchMinute], staffMinutes: List[StaffMinute], terminalName: TerminalName, queues: Seq[QueueName]): String = {

    val crunchMilliMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])] = CrunchApi.terminalMinutesByMinute(cms, terminalName)
    val staffMilliMinutes = CrunchApi
      .terminalMinutesByMinute(staffMinutes, terminalName)
      .map { case (minute, sms) => (minute, sms.head) }

    val staffBy15Minutes: Map[MillisSinceEpoch, StaffMinute] = groupStaffMinutesByX(15)(staffMilliMinutes, terminalName).toMap

    val groupCrunchMinutesBy15 = CrunchApi.groupCrunchMinutesByX(15)(crunchMilliMinutes, terminalName, queues.toList)
    val terminalCrunchMinuteRowsByMinute: Seq[(MillisSinceEpoch, Seq[String])] = groupCrunchMinutesBy15
      .collect { case (min, cm) =>
        val byQueue: Map[QueueName, Seq[CrunchMinute]] = cm.groupBy(_.queueName)
        val terminalQueuesRow: Seq[String] = queues.flatMap(qn => {
          byQueue.get(qn).map(thisQMinutes => {
            thisQMinutes.flatMap(cm => {
              List(
                s"${Math.round(cm.paxLoad)}",
                s"${Math.round(cm.waitTime)}",
                s"${cm.deskRec}",
                cm.actWait.map(aw => s"$aw").getOrElse(""),
                cm.actDesks.map(ad => s"$ad").getOrElse("")
              )
            })
          }).getOrElse(Nil)
        })

        (min, terminalQueuesRow)
      }
      .toList
      .sortBy(m => m._1)
    val crunchMinutes: Seq[String] = addStaffMinutes(terminalCrunchMinuteRowsByMinute, staffBy15Minutes, crunchMilliMinutes)

    crunchMinutes.mkString(lineEnding)
  }

  def addStaffMinutes(
                       terminalCrunchMinuteRowsByMinute: Seq[(MillisSinceEpoch, Seq[String])],
                       staffMinutesByMinute: Map[MillisSinceEpoch, StaffMinute],
                       crunchMilliMinutes: Seq[(MillisSinceEpoch, List[CrunchMinute])]
                     ): Seq[QueueName] = {
    terminalCrunchMinuteRowsByMinute
      .map {
        case (minute, queueData) =>

          val staffMinute = staffMinutesByMinute.getOrElse(minute, StaffMinute.empty)

          val staffData: Seq[String] = List(staffMinute.fixedPoints.toString, staffMinute.movements.toString, staffMinute.available.toString)
          val reqForMinute = crunchMilliMinutes
            .toList
            .find { case (minuteMilli, _) => minuteMilli == minute }
            .map { case (_, queueCrunchMinutes) => DesksAndQueues.totalRequired(staffMinute, queueCrunchMinutes) }
            .getOrElse(0)

          val hoursAndMinutes = SDate(minute, europeLondonTimeZone).toHoursAndMinutes()
          val queueFields = queueData.mkString(",")
          val pcpFields = staffData.mkString(",")
          val dateString = SDate(minute, europeLondonTimeZone).toISODateOnly

          dateString + "," + hoursAndMinutes + "," + queueFields + "," + pcpFields + "," + reqForMinute
      }
  }

  def flightsWithSplitsToCSVWithHeadings(flightsWithSplits: List[ApiFlightWithSplits]): String =
    flightsWithSplitsToCSVHeadings + lineEnding + flightsWithSplitsToCSV(flightsWithSplits)

  def actualAPIHeadings(flightsWithSplits: List[ApiFlightWithSplits]): Seq[String] =
    flightsWithSplits.flatMap(f => actualAPISplitsAndHeadingsFromFlight(f).map(_._1)).distinct.sorted

  def actualAPISplitsAndHeadingsFromFlight(flightWithSplits: ApiFlightWithSplits): Set[(String, Double)] = flightWithSplits
    .splits
    .collect {
      case s: Splits if s.source == SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages =>
        s.splits.map(s => {
          val ptaq = PaxTypeAndQueue(s.passengerType, s.queueType)
          (s"API Actual - ${PaxTypesAndQueues.displayName(ptaq)}", s.paxCount)
        })
    }
    .flatten

  def actualAPISplitsForFlightInHeadingOrder(flight: ApiFlightWithSplits, headings: Seq[String]): Seq[Double] =
    headings.map(h => actualAPISplitsAndHeadingsFromFlight(flight).toMap.getOrElse(h, 0.0))
      .map(n => Math.round(n).toDouble)

  def actualAPIDataForFlights(flights: List[ApiFlightWithSplits], headings: Seq[String]): List[Seq[Double]] = flights
    .map(f => actualAPISplitsForFlightInHeadingOrder(f, headings))


  val queueNames: Seq[String] = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrderWithFastTrack)


  def flightsWithSplitsToCSVIncludingAPIDataWithHeadings(flightsWithSplits: List[ApiFlightWithSplits]): String =
    flightsWithSplitsToCSVHeadings + "," + actualAPIHeadings(flightsWithSplits) + lineEnding + flightsWithSplitsToCSV(flightsWithSplits)

  def flightsWithSplitsToCSVHeadings: String = {
    val headings = "IATA,ICAO,Origin,Gate/Stand,Status,Scheduled Date,Scheduled Time,Est Arrival,Act Arrival,Est Chox,Act Chox,Est PCP,Total Pax,PCP Pax," +
      headingsForSplitSource(queueNames, "API") + "," +
      headingsForSplitSource(queueNames, "Historical") + "," +
      headingsForSplitSource(queueNames, "Terminal Average")

    headings
  }

  def flightsWithSplitsToCSV(flightsWithSplits: List[ApiFlightWithSplits]): String = {
    val queueNames = ApiSplitsToSplitRatio.queuesFromPaxTypeAndQueue(PaxTypesAndQueues.inOrderWithFastTrack)

    val csvData = flightsWithSplits.sortBy(_.apiFlight.PcpTime).map(fws => {

      flightToCsvRow(queueNames, fws)
    })
    asCSV(csvData)
  }

  def asCSV(csvData: List[List[Any]]): String = csvData.map(_.mkString(",")).mkString(lineEnding)

  def flightsWithSplitsWithAPIActualsToCSVWithHeadings(flights: List[ApiFlightWithSplits]): String = {
    val apiHeadings = actualAPIHeadings(flights)
    val headings = flightsWithSplitsToCSVHeadings + "," + apiHeadings.mkString(",")
    val csvData = flights.map(f => {
      flightToCsvRow(queueNames, f).mkString(",") + "," + actualAPISplitsForFlightInHeadingOrder(f, apiHeadings).mkString(",")

    })

    headings + lineEnding + csvData.mkString(lineEnding)
  }


  def flightToCsvRow(queueNames: Seq[String], fws: ApiFlightWithSplits): List[Any] = {
    List(
      fws.apiFlight.IATA,
      fws.apiFlight.ICAO,
      fws.apiFlight.Origin,
      fws.apiFlight.Gate.getOrElse("") + "/" + fws.apiFlight.Stand.getOrElse(""),
      fws.apiFlight.Status,
      Try(SDate(fws.apiFlight.Scheduled, europeLondonTimeZone).toISODateOnly).getOrElse(""),
      Try(SDate(fws.apiFlight.Scheduled, europeLondonTimeZone).toHoursAndMinutes()).getOrElse(""),
      fws.apiFlight.Estimated.map(SDate(_, europeLondonTimeZone).toHoursAndMinutes()).getOrElse(""),
      fws.apiFlight.Actual.map(SDate(_, europeLondonTimeZone).toHoursAndMinutes()).getOrElse(""),
      fws.apiFlight.EstimatedChox.map(SDate(_, europeLondonTimeZone).toHoursAndMinutes()).getOrElse(""),
      fws.apiFlight.ActualChox.map(SDate(_, europeLondonTimeZone).toHoursAndMinutes()).getOrElse(""),
      fws.apiFlight.PcpTime.map(SDate(_, europeLondonTimeZone).toHoursAndMinutes()).getOrElse(""),
      fws.apiFlight.ActPax.getOrElse(0),
      ArrivalHelper.bestPax(fws.apiFlight)
    ) ++
      queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).getOrElse(q, "")}") ++
      queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.Historical).getOrElse(q, "")}") ++
      queueNames.map(q => s"${queuePaxForFlightUsingSplits(fws, SplitRatiosNs.SplitSources.TerminalAverage).getOrElse(q, "")}")
  }

  def queuePaxForFlightUsingSplits(fws: ApiFlightWithSplits, splitSource: String): Map[QueueName, Int] =
    fws
      .splits
      .find(_.source == splitSource)
      .map(splits => ApiSplitsToSplitRatio.flightPaxPerQueueUsingSplitsAsRatio(splits, fws.apiFlight))
      .getOrElse(Map())

  def headingsForSplitSource(queueNames: Seq[String], source: String): String = queueNames
    .map(q => {
      val queueName = Queues.queueDisplayNames(q)
      s"$source $queueName"
    })
    .mkString(",")

  def multiDayToSingleExport(exportDays: Seq[Future[Option[String]]]): Future[String] = Future.sequence(
    exportDays.map(fd => fd.recoverWith {
      case e =>
        log.error(s"Failed to recover data for day ${e.getMessage}")
        Future(None)
    })).map(_.collect {
    case Some(s) => s
  }.mkString(lineEnding))

  def multiDayToSingleExport(exportDays: Future[Seq[Option[String]]]): Future[String] =
    exportDays.map {
      _.collect {
        case Some(s) => s
      }.mkString(lineEnding)
    }
}
