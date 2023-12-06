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
  def flightsToDailySummaryRow_(port: PortCode,
                                maybeTerminal: Option[Terminal],
                                start: LocalDate,
                                end: LocalDate,
                                passengerLoadsProvider: LocalDate => Future[Iterable[PassengersMinute]]
                               )
                               (implicit ec: ExecutionContext): (LocalDate, Int) => Future[Seq[String]] = {
    val regionName = PortRegion.fromPort(port).name
    val portName = port.toString
    val maybeTerminalName = maybeTerminal.map(_.toString)
    (localDate, totalPax) => {
      if (start <= localDate && localDate <= end)
        passengerLoadsProvider(localDate).map { passengerLoads =>
          val date = localDate.toISOString
          val queuePax = queueTotals(passengerLoads)
          val queueCells = Queues.queueOrder
            .map(queue => queuePax.getOrElse(queue, 0).toString)
            .mkString(",")
          val pcpPax = queuePax.values.sum
          val transPax = if (totalPax >= pcpPax) totalPax - pcpPax else 0

          maybeTerminalName match {
            case Some(terminalName) =>
              Seq(s"$date,$regionName,$portName,$terminalName,$totalPax,$pcpPax,$transPax,$queueCells\n")
            case None =>
              Seq(s"$date,$regionName,$portName,$totalPax,$pcpPax,$transPax,$queueCells\n")
          }
        }
      else Future.successful(Seq())
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

  val passengersToSummary: (Int, Iterable[PassengersMinute]) => (Int, Int, Int, Map[Queue, Int]) =
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

  val reducePassengerMinutesToSummary: Seq[(Int, Iterable[PassengersMinute])] => (Int, Int, Int, Map[Queue, Int]) = _
    .map(passengersToSummary.tupled)
    .reduce(reduceDailyPassengerSummaries)

  def paxMinutesToDailyRows(port: PortCode, maybeTerminal: Option[Terminal]): Seq[(LocalDate, Int, Iterable[PassengersMinute])] => String = {
    val regionName = PortRegion.fromPort(port).name
    val portName = port.toString
    val maybeTerminalName = maybeTerminal.map(_.toString)
    paxMinutes =>
      val date = paxMinutes.head._1.toISOString
      val queuePax = queueTotals(paxMinutes.flatMap(_._3))
      val queueCells = Queues.queueOrder
        .map(queue => queuePax.getOrElse(queue, 0).toString)
        .mkString(",")
      val pcpPax = queuePax.values.sum
      val transPax = paxMinutes.head._2 - pcpPax

      maybeTerminalName match {
        case Some(terminalName) =>
          s"$date,$regionName,$portName,$terminalName,${paxMinutes.head._2},$pcpPax,$transPax,$queueCells\n"
        case None =>
          s"$date,$regionName,$portName,${paxMinutes.head._2},$pcpPax,$transPax,$queueCells\n"
      }
  }

  def dailyPassengerMinutes(start: LocalDate,
                            end: LocalDate,
                            passengerLoadsProvider: LocalDate => Future[Iterable[PassengersMinute]]
                           )
                           (implicit ec: ExecutionContext): (LocalDate, Int) => Future[Seq[(LocalDate, Int, Iterable[PassengersMinute])]] = {
    (localDate, totalPax) => {
      if (start <= localDate && localDate <= end)
        passengerLoadsProvider(localDate).map(pl => Seq((localDate, totalPax, pl)))
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

  def totalPassengerCountProvider(utcFlightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
                                  paxFeedSourceOrder: List[FeedSource],
                                 ): (LocalDate, LocalDate) => Source[(LocalDate, Int), NotUsed] = {
    val transformer = relevantPaxDuringWindow(paxFeedSourceOrder)
    LocalDateStream(utcFlightsProvider, startBufferDays = 0, endBufferDays = 0, transformData = transformer)
  }

  //  def totalPassengerCountProvider(utcFlightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],
  //                                  paxFeedSourceOrder: List[FeedSource],
  //                                 ): (LocalDate, LocalDate) => Source[(LocalDate, Int), NotUsed] = {
  //    val transformer = relevantPaxDuringWindow(paxFeedSourceOrder)
  //    LocalDateStream(utcFlightsProvider, startBufferDays = 0, endBufferDays = 0, transformData = transformer)
  //  }

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
