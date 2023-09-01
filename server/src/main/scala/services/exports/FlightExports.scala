package services.exports

import akka.NotUsed
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.{MillisSinceEpoch, PassengersMinute}
import services.graphstages.Crunch
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, PortCode, PortRegion, Queues}
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

object FlightExports {
  def dateAndFlightsToCsvRows(port: PortCode,
                              terminal: Terminal,
                              paxFeedSourceOrder: List[FeedSource],
                             ): (LocalDate, Seq[ApiFlightWithSplits]) => Future[Seq[String]] = {
    val toCsv = FlightExports.flightsToCsvRows(port, terminal, paxFeedSourceOrder)
    (_, flights) => Future.successful(toCsv(flights))
  }

  private val flightsToCsvRows: (PortCode, Terminal, List[FeedSource]) => Seq[ApiFlightWithSplits] => Seq[String] =
    (port, terminal, paxFeedSourceOrder) => {
      val portName = port.iata
      val terminalName = terminal.toString
      val toRow = flightWithSplitsToCsvFields(paxFeedSourceOrder)
      flights =>
        flights
          .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
          .map(fws => s"$portName,$terminalName,${toRow(fws.apiFlight).mkString(",")}\n")
    }

  def flightsToDailySummaryRows(port: PortCode,
                                terminal: Terminal,
                                paxFeedSourceOrder: List[FeedSource],
                                passengerLoadsProvider: LocalDate => Future[Iterable[PassengersMinute]]
                               )
                               (implicit ec: ExecutionContext): (LocalDate, Seq[Arrival]) => Future[Seq[String]] = {
      val regionName = PortRegion.fromPort(port).name
      val portName = port.toString
      val terminalName = terminal.toString
      (localDate, arrivals) => {
        passengerLoadsProvider(localDate).map { passengerLoads =>
          val startMinute = SDate(localDate).millisSinceEpoch
          val endMinute = SDate(startMinute).addDays(1).addMinutes(-1).millisSinceEpoch
          val date = localDate.toISOString
          val totalPax = arrivals.map(arrival => totalPaxForArrivalInWindow(arrival, paxFeedSourceOrder, startMinute, endMinute)).sum
          val queuePax = queueTotals(passengerLoads)
          val queueCells = Queues.queueOrder
            .map(queue => queuePax.getOrElse(queue, 0).toString)
            .mkString(",")
          val pcpPax = queuePax.values.sum
          val transPax = totalPax - pcpPax

          Seq(s"$date,$regionName,$portName,$terminalName,$totalPax,$pcpPax,$transPax,$queueCells\n")
        }
      }
    }

  def totalPaxForArrivalInWindow(arrival: Arrival,
                                 paxFeedSourceOrder: List[FeedSource],
                                 startMinute: MillisSinceEpoch,
                                 endMinute: MillisSinceEpoch,
                                ): Int = {
    val total = arrival.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0)
    arrival.pcpRange(paxFeedSourceOrder).zipWithIndex
      .foldLeft(0) {
        case (acc, (minuteMillis, idx)) =>
          if (startMinute <= minuteMillis && minuteMillis <= endMinute)
            acc + paxForMinute(total, idx + 1)
          else acc
      }
  }

  def paxForMinute(total: Int, minute: Int): Int = {
    val minutesCount = (total.toDouble / 20).round.toInt
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

  def flightsProvider(utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed],
                      paxFeedSourceOrder: List[FeedSource],
                     ): (LocalDate, LocalDate, Terminal) => Source[(LocalDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end, terminal) => {
      val startMinute = SDate(start)
      val endMinute = SDate(end).addDays(1).addMinutes(-1)
      val utcStart = startMinute.addDays(-1).toUtcDate
      val utcEnd = SDate(end).addDays(2).toUtcDate
      utcFlightsProvider(utcStart, utcEnd, terminal)
        .sliding(3, 1)
        .map { days =>
          val utcDate = days.map(_._1).sorted.drop(1).head
          val localDate = LocalDate(utcDate.year, utcDate.month, utcDate.day)
          val flights = days.flatMap {
            case (_, fws) => fws.flights.values.filter { f =>
              val pcpStart = SDate(f.apiFlight.PcpTime.getOrElse(0L))
              val pcpMatches = pcpStart.toLocalDate == localDate
              lazy val isInRange = f.apiFlight.isRelevantToPeriod(startMinute, endMinute, paxFeedSourceOrder)
              pcpMatches && isInRange
            }
          }
          (localDate, flights)
        }
    }

  def flightWithSplitsToCsvFields(paxFeedSourceOrder: Seq[FeedSource]): Arrival => List[String] =
    arrival => List(
      arrival.flightCodeString,
      arrival.flightCodeString,
      arrival.Origin.toString,
      arrival.Gate.getOrElse("") + "/" + arrival.Stand.getOrElse(""),
      arrival.displayStatus.description,
      millisToLocalDateTimeString(arrival.Scheduled),
      arrival.predictedTouchdown.map(p => millisToLocalDateTimeString(p)).getOrElse(""),
      arrival.Estimated.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.Actual.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.EstimatedChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.ActualChox.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.differenceFromScheduled.map(_.toMinutes.toString).getOrElse(""),
      arrival.PcpTime.map(millisToLocalDateTimeString(_)).getOrElse(""),
      arrival.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.map(_.toString).getOrElse(""),
    )

  def millisToLocalDateTimeString: MillisSinceEpoch => String =
    (millis: MillisSinceEpoch) => SDate(millis, Crunch.europeLondonTimeZone).toLocalDateTimeString
}
