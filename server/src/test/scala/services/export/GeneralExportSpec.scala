package services.`export`

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.immutable.Seq
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class GeneralExportSpec extends CrunchTestLike {
  "Given a start and end date, a set of ports & terminals, an aggregator function and a flights provider" >> {
    "I should get rows for each terminal and aggregation level" >> {
      val sourceOrder = List(LiveFeedSource, ApiFeedSource, ForecastFeedSource)
      val start = LocalDate(2020, 1, 1)
      val end = LocalDate(2020, 1, 2)
      val aggregator: (PortCode, Terminal) => Seq[ApiFlightWithSplits] => Seq[String] = (port, terminal) => {
        val region = PortRegion.fromPort(port)
        flights =>
          flights
            .sortBy(_.apiFlight.PcpTime.getOrElse(0L))
            .map(fws => s"${region.name},${port.toString},${terminal.toString},${fws.apiFlight.flightCode.toString}\n")
      }
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

      def flightsProvider(utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed],
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
                  lazy val isInRange = f.apiFlight.isRelevantToPeriod(startMinute, endMinute, sourceOrder)
                  pcpMatches && isInRange
                }
              }
              (localDate, flights)
            }
        }

      val flightsToCsvLines: Seq[ApiFlightWithSplits] => Seq[String] = aggregator(port, terminal)

      val csvStream = toCsv(start, end, terminal, flightsProvider(utcFlightsProvider), flightsToCsvLines)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002
          |North,MAN,T1,BA0001
          |""".stripMargin
      )

      result === expected
    }
  }

  private def toCsv[A](start: LocalDate,
                       end: LocalDate,
                       terminal: Terminal,
                       flightsProvider: (LocalDate, LocalDate, Terminal) => Source[(LocalDate, A), NotUsed],
                       flightsToCsvLines: A => Seq[String]
                      ) = {
    flightsProvider(start, end, terminal).map {
      case (localDate, flights) => flightsToCsvLines(flights).mkString
    }
  }
}
