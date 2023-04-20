package services

import drt.shared.CrunchApi._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch.europeLondonTimeZone
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.time.SDate


object CSVData {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val lineEnding = "\n"

  def forecastHeadlineToCSV(headlines: ForecastHeadlineFigures, queueOrder: List[Queue]): String = {
    val headings = "," + headlines.queueDayHeadlines.map(_.day).toSet.toList.sorted.map(
      day => {
        val localDate = SDate(day, europeLondonTimeZone)
        f"${localDate.getDate}%02d/${localDate.getMonth}%02d"
      }
    ).mkString(",")
    val queues: String = queueOrder.flatMap(
      q => {
        headlines.queueDayHeadlines.groupBy(_.queue).get(q).map(
          qhls => (s"${Queues.displayName(q)}" ::
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
    (millis: MillisSinceEpoch) => SDate(millis, europeLondonTimeZone).toHoursAndMinutes

  private def periodTimeslotsToCSVString(timeSlotStarts: Seq[String], byTimeSlot: List[List[Option[ForecastTimeSlot]]]): String = {
    byTimeSlot.zip(timeSlotStarts).map {
      case (row, startTime) =>
        s"$startTime" + "," +
          row.map(forecastTimeSlotOptionToCSV).mkString(",")
    }.mkString(lineEnding)
  }

  private def forecastTimeSlotOptionToCSV(rowOption: Option[ForecastTimeSlot]): String = rowOption match {
    case Some(col) =>
      s"${col.available},${col.required},${col.available - col.required}"
    case None =>
      s",,"
  }

  private def makeDayHeadingsForPlanningExport(daysInPeriod: Seq[MillisSinceEpoch]): String = {
    "," + daysInPeriod.map(day => {
      val localDate = SDate(day, europeLondonTimeZone)
      val date = localDate.getDate
      val month = localDate.getMonth
      val columnPrefix = f"$date%02d/$month%02d - "
      columnPrefix + "available," + columnPrefix + "required," + columnPrefix + "difference"
    }).mkString(",")
  }

  private def forecastDaysInPeriod(forecastPeriod: ForecastPeriod): Seq[MillisSinceEpoch] = forecastPeriod.days.toList.map(_._1).sorted
}
