package services.liveviews

import actors.PartitionedPortStateActor.GetStateForDateRange
import akka.Done
import akka.actor.ActorRef
import akka.pattern.StatusReply.Ack
import akka.pattern.{StatusReply, ask}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PassengersMinute}
import drt.shared.{CrunchApi, TQM}
import org.slf4j.LoggerFactory
import slickdb.Tables
import uk.gov.homeoffice.drt.db.queries.PassengersHourlyDao
import uk.gov.homeoffice.drt.db.serialisers.PassengersHourlySerialiser
import uk.gov.homeoffice.drt.db.{PassengersHourly, PassengersHourlyRow}
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.time.TimeZoneHelper.utcTimeZone
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object PassengersLiveView {
  private val log = LoggerFactory.getLogger(getClass)

  def minutesContainerToHourlyRows(port: PortCode, nowMillis: () => Long): MinutesContainer[PassengersMinute, TQM] => Iterable[PassengersHourlyRow] =
    container => {
      val updatedAt = nowMillis()

      container.minutes
        .groupBy { minute =>
          val sdate = SDate(minute.key.minute, utcTimeZone)
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
                    (implicit ec: ExecutionContext): MinutesContainer[CrunchApi.PassengersMinute, TQM] => Future[StatusReply[Done]] = {
    val replaceHours = PassengersHourlyDao.replaceHours(portCode)
    val containerToHourlyRows = PassengersLiveView.minutesContainerToHourlyRows(portCode, () => now().millisSinceEpoch)

    container =>
      val eventuals = container.minutes.groupBy(_.key.terminal).map {
        case (terminal, terminalMinutes) =>
          val hoursToReplace = containerToHourlyRows(MinutesContainer(terminalMinutes))
          db.run(replaceHours(terminal, hoursToReplace))
      }
      Future.sequence(eventuals).map(_ => Ack)
  }

  def populateHistoricPax(updateForDate: UtcDate => Future[StatusReply[Done]])
                         (implicit mat: Materializer): Future[Done] = {
    val today = SDate.now()
    val oneYearDays = 365
    val historicDaysToPopulate = oneYearDays * 6

    Source(1 to historicDaysToPopulate)
      .mapAsync(1)(day => updateForDate(today.addDays(-1 * day).toUtcDate))
      .run()
  }

  def populatePaxForDate(minutesActor: ActorRef, update: MinutesContainer[PassengersMinute, TQM] => Future[StatusReply[Done]])
                        (implicit ec: ExecutionContext, timeout: Timeout): UtcDate => Future[StatusReply[Done]] =
    utcDate => {
      val sdate = SDate(utcDate)
      val request = GetStateForDateRange(sdate.millisSinceEpoch, sdate.addDays(1).addMinutes(-1).millisSinceEpoch)
      minutesActor
        .ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]]
        .flatMap { container =>
          if (container.minutes.size < MilliTimes.oneDayMillis) {
            val paxMins = MinutesContainer(
              container.minutes.map(cm => PassengersMinute(cm.terminal, cm.key.queue, cm.minute, Seq.fill(cm.toMinute.paxLoad.round.toInt)(1), None))
            )
            log.info(s"Populating pax for ${utcDate.toISOString}")
            update(paxMins)
          } else {
            log.info(s"No pax for ${utcDate.toISOString}")
            Future.successful(Ack)
          }
        }
        .recover {
          case t: Throwable =>
            log.error(s"Error populating pax for ${utcDate.toISOString}", t)
            Ack
        }
    }
}
