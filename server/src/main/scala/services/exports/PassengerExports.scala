package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CodeShares
import drt.shared.CrunchApi.{MillisSinceEpoch, PassengersMinute}
import uk.gov.homeoffice.drt.arrivals.{Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode, PortRegion, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object PassengerExports {
  def flightsToDailySummaryRow(port: PortCode,
                               terminal: Terminal,
                               start: LocalDate,
                               end: LocalDate,
                               passengerLoadsProvider: (LocalDate, Terminal) => Future[Iterable[PassengersMinute]]
                              )
                              (implicit ec: ExecutionContext): (LocalDate, Int) => Future[Seq[String]] = {
    val regionName = PortRegion.fromPort(port).name
    val portName = port.toString
    val terminalName = terminal.toString
    (localDate, totalPax) => {
      if (start <= localDate && localDate <= end)
        passengerLoadsProvider(localDate, terminal).map { passengerLoads =>
          val date = localDate.toISOString
          val queuePax = queueTotals(passengerLoads)
          val queueCells = Queues.queueOrder
            .map(queue => queuePax.getOrElse(queue, 0).toString)
            .mkString(",")
          val pcpPax = queuePax.values.sum
          val transPax = if (totalPax >= pcpPax) totalPax - pcpPax else 0

          Seq(s"$date,$regionName,$portName,$terminalName,$totalPax,$pcpPax,$transPax,$queueCells\n")
        }
      else Future.successful(Seq())
    }
  }

  def paxForMinute(total: Int, minute: Int): Int = {
    val minutesCount = (total.toDouble / 20).ceil.toInt
    if (1 <= minute && minute <= minutesCount) {
      val totalForMinutes = minute * 20
      if (totalForMinutes <= total) 20 else 20 - (totalForMinutes - total)
    } else 0
  }

  def queueTotals(minutes: Iterable[PassengersMinute]): Map[Queue, Int] =
    minutes
      .map(pm => (pm.queue, pm.passengers.size))
      .groupBy(_._1)
      .view.mapValues(x => x.map(_._2).sum).toMap

  def totalPassengerCountProvider(utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed],
                                  paxFeedSourceOrder: List[FeedSource],
                                 ): (LocalDate, LocalDate, Terminal) => Source[(LocalDate, Int), NotUsed] = {
    (start, end, terminal) => {
      val startMinute = SDate(start)
      val utcStart = startMinute.addDays(-1).toUtcDate
      val utcEnd = SDate(end).addDays(2).toUtcDate
      utcFlightsProvider(utcStart, utcEnd, terminal)
        .sliding(3, 1)
        .map { days =>
          val utcDate = days.map(_._1).sorted.drop(1).head
          val localDate = LocalDate(utcDate.year, utcDate.month, utcDate.day)
          val windowStart = SDate(localDate)
          val windowEnd = SDate(localDate).addDays(1).addMinutes(-1)
          val arrivals = days.flatMap(_._2.flights.values.map(_.apiFlight))
          val totalPax = relevantPaxDuringWindow(arrivals, windowStart, windowEnd, paxFeedSourceOrder)
          (localDate, totalPax)
        }
    }
  }

  def relevantPaxDuringWindow(flights: Seq[Arrival],
                              windowStart: SDateLike,
                              windowEnd: SDateLike,
                              paxFeedSourceOrder: List[FeedSource],
                             ): Int = {
    val arrivals = flights.filter(_.hasPcpDuring(windowStart, windowEnd, paxFeedSourceOrder))
    val uniqueArrivals = CodeShares.uniqueArrivals[Arrival](identity, paxFeedSourceOrder)(arrivals).toSeq

    uniqueArrivals
      .sortBy(_.PcpTime.getOrElse(0L))
      .map(arrival => totalPaxForArrivalInWindow(arrival, paxFeedSourceOrder, windowStart.millisSinceEpoch, windowEnd.millisSinceEpoch))
      .sum
  }

  def totalPaxForArrivalInWindow(arrival: Arrival,
                                 paxFeedSourceOrder: List[FeedSource],
                                 startMinute: MillisSinceEpoch,
                                 endMinute: MillisSinceEpoch,
                                ): Int =
    if (!arrival.Origin.isDomesticOrCta && !arrival.isCancelled) {
      val total = arrival.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0)
      arrival.pcpRange(paxFeedSourceOrder).zipWithIndex
        .foldLeft(0) {
          case (acc, (minuteMillis, idx)) =>
            if (startMinute <= minuteMillis && minuteMillis <= endMinute) {
              acc + paxForMinute(total, idx + 1)
            } else acc
        }
    } else 0
}
