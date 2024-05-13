package services.exports

import drt.shared.CrunchApi._
import drt.shared.PortState
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

object Forecast {
  def headlineFigures(startOfForecast: SDateLike,
                      numberOfDays: Int,
                      terminal: Terminal,
                      portState: PortState,
                      queues: List[Queue]): ForecastHeadlineFigures = {
    val crunchSummaryDaily = portState.dailyCrunchSummary(startOfForecast, numberOfDays, terminal, queues)

    val figures = for {
      (dayMillis, queueMinutes) <- crunchSummaryDaily
      (queue, queueMinute) <- queueMinutes
    } yield {
      QueueHeadline(dayMillis, queue, queueMinute.paxLoad.toInt, queueMinute.workLoad.toInt)
    }
    ForecastHeadlineFigures(figures.toSeq)
  }

  def forecastPeriod(airportConfig: AirportConfig,
                     terminal: Terminal,
                     startOfForecast: SDateLike,
                     endOfForecast: SDateLike,
                     portState: PortState,
                     forecastPeriod: Int): ForecastPeriod = {
    val fifteenMinuteMillis = forecastPeriod * 60 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / fifteenMinuteMillis
    val staffSummary = portState.staffSummary(startOfForecast, periods, forecastPeriod, terminal)
    val crunchSummary15Mins = portState.crunchSummary(startOfForecast, periods, forecastPeriod, terminal, airportConfig.nonTransferQueues(terminal).toList)
    val timeSlotsByDay = Forecast.rollUpForWeek(crunchSummary15Mins, staffSummary)
    ForecastPeriod(timeSlotsByDay)
  }

  def rollUpForWeek(crunchSummary: Map[MillisSinceEpoch, Map[Queue, CrunchMinute]],
                    staffSummary: Map[MillisSinceEpoch, StaffMinute]
                   ): Map[MillisSinceEpoch, Seq[ForecastTimeSlot]] =
    crunchSummary
      .map { case (millis, cms) =>
        val (available, fixedPoints) = staffSummary.get(millis).map(sm => (sm.shifts, sm.fixedPoints)).getOrElse((0, 0))
        val deskStaff = if (cms.nonEmpty) cms.values.map(_.deskRec).sum else 0
        ForecastTimeSlot(millis, available, fixedPoints + deskStaff)
      }
      .groupBy(forecastTimeSlot => SDate(forecastTimeSlot.startMillis).getLocalLastMidnight.millisSinceEpoch)
      .view.mapValues(_.toSeq).toMap
}
