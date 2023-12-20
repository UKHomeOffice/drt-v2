package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CodeShares
import drt.shared.CrunchApi.{MillisSinceEpoch, PassengersMinute}
import services.LocalDateStream
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode, PortRegion, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object PassengerExports {
  val paxSummaryToRow: (PortCode, Option[Terminal]) => (Int, Int, Int, Map[Queue, Int]) => String = {
    (portCode, maybeTerminal) => {
      val regionName = PortRegion.fromPort(portCode).name
      val portCodeStr = portCode.toString
      val maybeTerminalName = maybeTerminal.map(_.toString)
      (totalPax, pcpPax, transPax, queuePax) => {
        val queueCells = Queues.queueOrder
          .map(queue => queuePax.getOrElse(queue, 0).toString)
          .mkString(",")

        maybeTerminalName match {
          case Some(terminalName) =>
            s"$regionName,$portCodeStr,$terminalName,$totalPax,$pcpPax,$transPax,$queueCells\n"
          case None =>
            s"$regionName,$portCodeStr,$totalPax,$pcpPax,$transPax,$queueCells\n"
        }
      }
    }
  }

  val reduceDailyPassengerSummaries: ((Int, Int, Int, Map[Queue, Int]), (Int, Int, Int, Map[Queue, Int])) => (Int, Int, Int, Map[Queue, Int]) =
    (a, b) => {
      val queueCells = Queues.queueOrder
        .map { queue =>
          val existing = a._4.getOrElse(queue, 0)
          val toAdd = b._4.getOrElse(queue, 0)
          (queue, existing + toAdd)
        }.toMap
      (a._1 + b._1, a._2 + b._2, a._3 + b._3, queueCells)
    }

  private val passengersToSummary: (Int, Iterable[PassengersMinute]) => (Int, Int, Int, Map[Queue, Int]) =
    (totalPax, paxMinutes) => {
      val queuePax = queueTotals(paxMinutes)
      val pcpPax = queuePax.values.sum
      val transPax = if (totalPax >= pcpPax) totalPax - pcpPax else 0
      val queueCells = Queues.queueOrder
        .map { queue =>
          (queue, queuePax.getOrElse(queue, 0))
        }.toMap
      (totalPax, pcpPax, transPax, queueCells)
    }

  def dateToSummary(passengerLoadsProvider: LocalDate => Future[Iterable[PassengersMinute]])
                   (implicit ec: ExecutionContext): (LocalDate, Int) => Future[(Int, Int, Int, Map[Queue, Int])] =
    (localDate, totalPax) =>
      passengerLoadsProvider(localDate).map(passengersToSummary(totalPax, _))

  def paxMinutesToDailyRows(port: PortCode, maybeTerminal: Option[Terminal]): (LocalDate, Int, Iterable[PassengersMinute]) => String = {
    (date, totalPax, minutes) =>
      val row = paxSummaryToRow(port, maybeTerminal).tupled(passengersToSummary(totalPax, minutes))
      val dateStr = date.ddmmyyyy
      s"$dateStr,$row"
  }

  def dailyPassengerMinutes(passengerLoadsProvider: LocalDate => Future[Iterable[PassengersMinute]])
                           (implicit ec: ExecutionContext): (LocalDate, Int) => Future[(LocalDate, Int, Iterable[PassengersMinute])] =
    (localDate, totalPax) =>
      passengerLoadsProvider(localDate).map(pl => (localDate, totalPax, pl))


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

  def totalPassengerCountProvider(utcFlightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                  paxFeedSourceOrder: List[FeedSource],
                                 ): (LocalDate, LocalDate) => Source[(LocalDate, Int), NotUsed] = {
    val transformer = relevantPaxDuringWindow(paxFeedSourceOrder)
    LocalDateStream(utcFlightsProvider, startBufferDays = 1, endBufferDays = 1, transformData = transformer)
  }

  def relevantPaxDuringWindow(paxFeedSourceOrder: List[FeedSource]): (LocalDate, Seq[ApiFlightWithSplits]) => Int =
    (current, flights) => {
      val windowStart = SDate(current)
      val windowEnd = SDate(current).addDays(1).addMinutes(-1)
      val arrivals = flights.collect {
        case fws if fws.apiFlight.hasPcpDuring(windowStart, windowEnd, paxFeedSourceOrder) => fws
      }
      val uniqueArrivals = CodeShares.uniqueArrivals(paxFeedSourceOrder)(arrivals).toSeq

      uniqueArrivals
        .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
        .map(arrival => totalPaxForArrivalInWindow(arrival.apiFlight, paxFeedSourceOrder, windowStart.millisSinceEpoch, windowEnd.millisSinceEpoch))
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
