package services.liveviews

import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.TQM
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.db.queries.PassengersHourlySerialiser
import uk.gov.homeoffice.drt.db.{PassengersHourly, PassengersHourlyRow}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.SDate

object PassengersLiveView {
  def minutesContainerToHourlyRows(port: PortCode, nowMillis: () => Long): MinutesContainer[PassengersMinute, TQM] => Iterable[PassengersHourlyRow] =
    container => {
      val updatedAt = nowMillis()

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
            val hourly = PassengersHourly(
              port,
              terminal,
              queue,
              date,
              hour,
              passengers,
            )
            PassengersHourlySerialiser.toRow(hourly, updatedAt)
        }
    }
}
