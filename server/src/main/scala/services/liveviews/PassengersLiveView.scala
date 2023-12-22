package services.liveviews

import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.TQM
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{SDate, UtcDate}

import java.sql.Timestamp

object PassengersLiveView {
  def minutesContainerToHourlyRow(port: PortCode): MinutesContainer[PassengersMinute, TQM] => Iterable[PassengersHourlyRow] =
    container =>
      container.minutes
        .groupBy { minute =>
          val sdate = SDate(minute.key.minute, Crunch.utcTimeZone)
          val t = minute.key.terminal
          val q = minute.key.queue
          val d = sdate.toUtcDate
          val h = sdate.getHours
          (t, q, d, h)
        }
        .map {
          case ((terminal, queue, date, hour), minutes) =>
            val passengers = minutes.map(_.toMinute.passengers.size).sum
            PassengersHourlyRow(
              port.iata,
              terminal.toString,
              queue.toString,
              date,
              hour,
              passengers,
              None,
              None,
            )
        }
}
