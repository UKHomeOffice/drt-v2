package services.`export`

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import passengersplits.WholePassengerQueueSplits
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.ports.Queues.{Open, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.MilliTimes.oneMinuteMillis
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class GeneralExportSpec extends CrunchTestLike {
  "Given a start and end date, a set of ports & terminals, an aggregator function and a flights provider" >> {
    "I should get rows for each terminal and aggregation level" >> {
      val sourceOrder: List[FeedSource] = List(LiveFeedSource)
      val start = LocalDate(2020, 1, 1)
      val end = LocalDate(2020, 1, 2)
      val port = PortCode("MAN")
      val terminal = Terminal("T1")

      val utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed] = (start, end, terminal) => {
        val paxSources = Map[FeedSource, Passengers](LiveFeedSource -> Passengers(Option(100), None))
        Source(List(
          (UtcDate(2020, 1, 1), FlightsWithSplits(Seq(
            ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = paxSources), Set()),
          ))),
          (UtcDate(2020, 1, 2), FlightsWithSplits(Seq(
            ApiFlightWithSplits(ArrivalGenerator.arrival(iata = "BA0002", schDt = "2020-01-02T00:05", pcpDt = "2020-01-02T00:30", passengerSources = paxSources), Set()),
          ))),
        ))
      }

      val flightsToCsvLines: (LocalDate, Seq[ApiFlightWithSplits]) => Seq[String] =
        (_, flights) => FlightExports.flightsToCsvRows(port, terminal)(flights)

      val csvStream = GeneralExport.toCsv(start, end, terminal, FlightExports.flightsProvider(utcFlightsProvider, sourceOrder), flightsToCsvLines)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002
          |North,MAN,T1,BA0001
          |""".stripMargin
      )

      result === expected
    }
  }
}

object GeneralExport {
  def toCsv[A](start: LocalDate,
               end: LocalDate,
               terminal: Terminal,
               dataStream: (LocalDate, LocalDate, Terminal) => Source[(LocalDate, A), NotUsed],
               dataToCsvRows: (LocalDate, A) => Seq[String]
              ): Source[String, NotUsed] =
    dataStream(start, end, terminal).map {
      case (localDate, flights) => dataToCsvRows(localDate, flights).mkString
    }
}

object FlightExports {
  val flightsToCsvRows: (PortCode, Terminal) => Seq[ApiFlightWithSplits] => Seq[String] = (port, terminal) => {
    val region = PortRegion.fromPort(port)
    flights =>
      flights
        .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
        .map(fws => s"${region.name},${port.toString},${terminal.toString},${fws.apiFlight.flightCode.toString}\n")
  }

  val flightsToDailySummaryRows: (PortCode, Terminal, List[FeedSource]) => (LocalDate, Seq[ApiFlightWithSplits]) => Seq[String] =
    (port, terminal, paxFeedSourceOrder) => {
      val regionName = PortRegion.fromPort(port).name
      val portName = port.toString
      val terminalName = terminal.toString
      (localDate, flights) => {
        val startMinute = SDate(localDate).millisSinceEpoch
        val endMinute = SDate(startMinute).addDays(1).addMinutes(-1).millisSinceEpoch
        val range = startMinute to endMinute by oneMinuteMillis
        val date = localDate.toISOString
        val totalPax = flights.map(_.apiFlight.bestPaxEstimate(paxFeedSourceOrder).passengers.actual.getOrElse(0)).sum
        val pcpPax = flights.map(_.apiFlight.bestPaxEstimate(paxFeedSourceOrder).passengers.getPcpPax.getOrElse(0)).sum
        val transPax = totalPax - pcpPax
        val queuePax = flights.foldLeft(Map.empty[Queue, Int]) { case (acc, fws) =>
          val flightQueueSplits = WholePassengerQueueSplits
            .flightSplits(range, fws, (_, _) => 0d, (_, _) => Open, Queues.QueueFallbacks(Map()), paxFeedSourceOrder)
            .view.mapValues { paxByMinute => paxByMinute.view.filterKeys(m => startMinute <= m && m <= endMinute).map(_._2.size).sum }
          flightQueueSplits.foldLeft(acc) { case (acc, (queue, pax)) =>
            acc.updated(queue, acc.getOrElse(queue, 0) + pax)
          }
        }
        val queueCells = Queues.queueOrder
          .map(queue => queuePax.getOrElse(queue, 0).toString)
          .mkString(",")

        Seq(s"$date,$regionName,$portName,$terminalName,$totalPax,$pcpPax,$transPax,$queueCells\n")
      }
    }

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
}
