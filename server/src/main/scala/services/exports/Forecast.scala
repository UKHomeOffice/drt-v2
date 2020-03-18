package services.exports

import drt.shared.CrunchApi._
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{AirportConfig, PortState, SDateLike}
import services.SDate

object Forecast {
  def headlineFigures(startOfForecast: SDateLike,
                      endOfForecast: SDateLike,
                      terminal: Terminal,
                      portState: PortState,
                      queues: List[Queue]): ForecastHeadlineFigures = {
    val dayMillis = 60 * 60 * 24 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / dayMillis
    val crunchSummaryDaily = portState.crunchSummary(startOfForecast, periods, 1440, terminal, queues)

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
                     portState: PortState): ForecastPeriod = {
    val fifteenMinuteMillis = 15 * 60 * 1000
    val periods = (endOfForecast.millisSinceEpoch - startOfForecast.millisSinceEpoch) / fifteenMinuteMillis
    val staffSummary = portState.staffSummary(startOfForecast, periods, 15, terminal)
    val crunchSummary15Mins = portState.crunchSummary(startOfForecast, periods, 15, terminal, airportConfig.nonTransferQueues(terminal).toList)
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
      .mapValues(_.toSeq)
}
