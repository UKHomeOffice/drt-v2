package services.liveviews

import drt.shared.CrunchApi.{MinutesContainer, PassengersMinute}
import drt.shared.{CrunchApi, TQM}
import services.graphstages.Crunch
import slickdb.Tables
import uk.gov.homeoffice.drt.db.queries.{PassengersHourlyQueries, PassengersHourlySerialiser}
import uk.gov.homeoffice.drt.db.{AggregateDb, PassengersHourly, PassengersHourlyRow}
import uk.gov.homeoffice.drt.ports.{AirportConfig, PortCode}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.ExecutionContext

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

  def updateLiveView(portCode: PortCode, now: () => SDateLike, db: Tables)
                     (implicit ec: ExecutionContext): MinutesContainer[CrunchApi.PassengersMinute, TQM] => Unit = {
    val replaceHours = PassengersHourlyQueries.replaceHours(portCode)
    val containerToHourlyRow = PassengersLiveView.minutesContainerToHourlyRows(portCode, () => now().millisSinceEpoch)

    _.minutes.groupBy(_.key.terminal).foreach {
      case (terminal, terminalMinutes) =>
        val rows = containerToHourlyRow(MinutesContainer(terminalMinutes))
        db.run(replaceHours(terminal, rows))
    }
  }
}
