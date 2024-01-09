package services.liveviews

import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.Done
import akka.actor.ActorRef
import akka.pattern.ask
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PassengersMinute}
import drt.shared.{CrunchApi, TQM}
import org.slf4j.LoggerFactory
import services.graphstages.Crunch
import slickdb.Tables
import uk.gov.homeoffice.drt.db.queries.{PassengersHourlyQueries, PassengersHourlySerialiser}
import uk.gov.homeoffice.drt.db.{PassengersHourly, PassengersHourlyRow}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object PassengersLiveView {
  private val log = LoggerFactory.getLogger(getClass)

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

  def populateHistoricPax(updateForDate: UtcDate => Future[Unit])
                         (implicit mat: Materializer): Future[Done] = {
    val today = SDate.now()
    Source(1 to (365 * 6))
      .mapAsync(1)(day => updateForDate(today.addDays(-1 * day).toUtcDate))
      .run()
  }

  def populatePaxForDate(minutesActor: ActorRef, update: MinutesContainer[PassengersMinute, TQM] => Unit)
                        (implicit ec: ExecutionContext, timeout: Timeout): UtcDate => Future[Unit] =
    utcDate => {
      val sdate = SDate(utcDate)
      val request = GetStateForDateRange(sdate.millisSinceEpoch, sdate.getLocalNextMidnight.millisSinceEpoch)
      minutesActor
        .ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]]
        .map { container =>
          if (container.minutes.size < MilliTimes.oneDayMillis) {
            val paxMins = MinutesContainer(
              container.minutes.map(cm => PassengersMinute(cm.terminal, cm.key.queue, cm.minute, Seq.fill(cm.toMinute.paxLoad.round.toInt)(1), None))
            )
            update(paxMins)
            log.info(s"Populated pax for ${utcDate.toISOString}")
          } else log.info(s"No pax for ${utcDate.toISOString}")
        }
        .recover {
          case t: Throwable =>
            log.error(s"Error populating pax for ${utcDate.toISOString}", t)
        }
    }
}
