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
import drt.shared.{CodeShares, CrunchApi, TQM}
import org.slf4j.LoggerFactory
import slickdb.AggregatedDbTables
import uk.gov.homeoffice.drt.actor.state.ArrivalsState
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.db.queries.PassengersHourlyDao
import uk.gov.homeoffice.drt.db.serialisers.PassengersHourlySerialiser
import uk.gov.homeoffice.drt.db.{PassengersHourly, PassengersHourlyRow}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode}
import uk.gov.homeoffice.drt.time.TimeZoneHelper.utcTimeZone
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object PassengersLiveView {
  private val log = LoggerFactory.getLogger(getClass)

  def minutesContainerToHourlyRows(port: PortCode,
                                   nowMillis: () => Long,
                                  ): (MinutesContainer[PassengersMinute, TQM], Map[Terminal, Map[Int, Int]]) => Iterable[PassengersHourlyRow] =
    (container, capacities) => {
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
            val terminalCapacities = capacities.getOrElse(terminal, Map.empty[Int, Int])
            val hourly = PassengersHourly(
              port,
              terminal,
              queue,
              date,
              hour,
              passengers,
              terminalCapacities.getOrElse(hour, 0),
            )
            PassengersHourlySerialiser.toRow(hourly, updatedAt)
        }
    }

  def updateLiveView(portCode: PortCode, now: () => SDateLike, db: AggregatedDbTables)
                    (implicit ec: ExecutionContext): (MinutesContainer[CrunchApi.PassengersMinute, TQM], Map[Terminal, Map[Int, Int]]) => Future[StatusReply[Done]] = {
    val replaceHours = PassengersHourlyDao.replaceHours(portCode)
    val containerToHourlyRows = PassengersLiveView.minutesContainerToHourlyRows(portCode, () => now().millisSinceEpoch)

    (container, capacities) =>
      val eventuals = container.minutes.groupBy(_.key.terminal).map {
        case (terminal, terminalMinutes) =>
          val hoursToReplace: Iterable[PassengersHourlyRow] = containerToHourlyRows(MinutesContainer(terminalMinutes), capacities)
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

  def populatePaxForDate(minutesActor: ActorRef,
                         getCapacity: UtcDate => Future[Map[Terminal, Map[Int, Int]]],
                         update: (MinutesContainer[PassengersMinute, TQM], Map[Terminal, Map[Int, Int]]) => Future[StatusReply[Done]])
                        (implicit ec: ExecutionContext, timeout: Timeout): UtcDate => Future[StatusReply[Done]] =
    utcDate => {
      val sdate = SDate(utcDate)
      val request = GetStateForDateRange(sdate.millisSinceEpoch, sdate.addDays(1).addMinutes(-1).millisSinceEpoch)
      minutesActor
        .ask(request).mapTo[MinutesContainer[CrunchMinute, TQM]]
        .flatMap(cms => getCapacity(utcDate).map(c => (cms, c)))
        .flatMap { case (container, capacities) =>
          if (container.minutes.size < MilliTimes.oneDayMillis) {
            val paxMins = MinutesContainer(
              container.minutes.map(cm => PassengersMinute(cm.terminal, cm.key.queue, cm.minute, Seq.fill(cm.toMinute.paxLoad.round.toInt)(1), None))
            )
            log.info(s"Populating pax for ${utcDate.toISOString}")
            update(paxMins, capacities)
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

  def capacityForDate(flights: UtcDate => Future[Iterable[ApiFlightWithSplits]])
                     (implicit ec: ExecutionContext): UtcDate => Future[Map[Terminal, Map[Int, Int]]] =
    utcDate => {
      val dayBefore = SDate(utcDate).addDays(-1).toUtcDate
      flights(utcDate)
        .flatMap(fs1 => flights(dayBefore).map(fs2 => fs1 ++ fs2))
        .map(_.groupBy(_.apiFlight.Terminal))
        .map { byTerminal =>
          byTerminal.view
            .mapValues { fs =>
              capacityAndPcpTimes(fs)
                .filter {
                  case (pax, pcpTime) =>
                    def endsInWindow: Boolean = pcpTime.addMinutes((pax.toDouble / Arrival.paxOffPerMinute).floor.toInt).toUtcDate == utcDate

                    val startsInWindow = pcpTime.toUtcDate == utcDate
                    startsInWindow || endsInWindow
                }
                .foldLeft(Map.empty[Int, Int]) {
                  case (acc, (cap, pcp)) => addMinutePaxToHourAggregates(utcDate, acc, pcpMinutes(cap, pcp))
                }
            }
            .toMap
        }
    }

  def capacityAndPcpTimes(flights: Iterable[ApiFlightWithSplits]): Iterable[(Int, SDateLike)] =
    flights.map(flight =>
      (flight.apiFlight.MaxPax.getOrElse(0), SDate(flight.apiFlight.PcpTime.getOrElse(0L)))
    )

  def uniqueFlightsForDate(flights: UtcDate => Future[Iterable[ApiFlightWithSplits]],
                           baseArrivals: UtcDate => Future[ArrivalsState],
                           paxFeedSourceOrder: List[FeedSource],
                          )
                          (implicit ec: ExecutionContext): UtcDate => Future[Iterable[ApiFlightWithSplits]] =
    utcDate => {
      flights(utcDate)
        .map(_.filterNot(_.apiFlight.Origin.isDomesticOrCta))
        .map(flights => CodeShares.uniqueArrivals(paxFeedSourceOrder)(flights.toSeq))
        .flatMap(fs => populateMissingMaxPax(utcDate, baseArrivals, fs))
    }


  def populateMissingMaxPax(utcDate: UtcDate,
                            baseArrivals: UtcDate => Future[ArrivalsState],
                            flights: Iterable[ApiFlightWithSplits],
                           )
                           (implicit ec: ExecutionContext): Future[Iterable[ApiFlightWithSplits]] = {
    val pctWithoutMaxPax = (100 * flights.count(_.apiFlight.MaxPax.isDefined).toDouble / flights.size).round.toInt
    if (pctWithoutMaxPax < 5) {
      Future.successful(flights)
    } else {
      baseArrivals(utcDate).map { baseArrivals =>
        flights
          .map {
            case flight if flight.apiFlight.MaxPax.nonEmpty => flight
            case flight =>
              val maybeArrival = baseArrivals.arrivals.get(flight.apiFlight.unique)
              val maybeMaxPax = maybeArrival.flatMap(_.MaxPax)
              flight.copy(apiFlight = flight.apiFlight.copy(MaxPax = maybeMaxPax))
          }
      }
    }
  }

  def addMinutePaxToHourAggregates(utcDate: UtcDate, hourly: Map[Int, Int], capMinutes: Map[Long, Int]): Map[Int, Int] =
    capMinutes.foldLeft(hourly) {
      case (acc, (minuteMillis, pax)) =>
        val sdate = SDate(minuteMillis)
        if (sdate.toUtcDate == utcDate) {
          val hour = sdate.getHours
          acc.updated(hour, acc.getOrElse(hour, 0) + pax)
        } else {
          acc
        }
    }

  def pcpMinutes(pax: Int, pcpStart: SDateLike): Map[Long, Int] = {
    val disembarkingMinutes = (pax.toDouble / 20).ceil.toInt
    (0 until disembarkingMinutes).foldLeft(Map.empty[Long, Int]) {
      case (acc, i) =>
        val minuteMillis = pcpStart.addMinutes(i).millisSinceEpoch
        val remainingCapacity = pax - acc.values.sum
        val paxInMinute = if (remainingCapacity > 20) 20 else remainingCapacity
        acc.updated(minuteMillis, acc.getOrElse(minuteMillis, 0) + paxInMinute)
    }
  }
}
