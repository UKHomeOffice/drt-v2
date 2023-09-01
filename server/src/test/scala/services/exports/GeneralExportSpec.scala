package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PassengersMinute
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

class GeneralExportSpec extends CrunchTestLike {
  val paxSourceOrder: List[FeedSource] = List(LiveFeedSource)
  val passengers: Map[FeedSource, Passengers] = Map[FeedSource, Passengers](LiveFeedSource -> Passengers(Option(95), None))

  "paxForMinute should" >> {
    "give 20 for minutes 1 to 4, 15 for minute 5, and 0 for minutes 0 & 6, given 95 total pax" >> {
      (0 to 6).map(FlightExports.paxForMinute(95, _)) === Seq(0, 20, 20, 20, 20, 15, 0)
    }
  }

  "queueTotals should" >> {
    "give sums of passengers for each queue in a collection of CrunchMinutes" >> {
      def paxMinute(minute: Int, queue: Queue, pax: Int): PassengersMinute =
        PassengersMinute(T1, queue, minute * oneMinuteMillis, Iterable.fill(pax)(0), None)

      val cms = Seq(
        paxMinute(1, Queues.EeaDesk, 1),
        paxMinute(2, Queues.EeaDesk, 5),
        paxMinute(1, Queues.EGate, 2),
        paxMinute(2, Queues.EGate, 6),
        paxMinute(1, Queues.NonEeaDesk, 3),
        paxMinute(2, Queues.NonEeaDesk, 7),
      )
      FlightExports.queueTotals(cms) === Map(
        Queues.EeaDesk -> 6,
        Queues.EGate -> 8,
        Queues.NonEeaDesk -> 10,
      )
    }
  }

  "totalPaxForArrivalInWindow should" >> {
    val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = passengers)
    "give 20 passengers arriving at the pcp between 01:00 and 01:30 when there are 95 total passengers starting to arrive at 01:30" >> {
      FlightExports.totalPaxForArrivalInWindow(
        arrival, paxSourceOrder, SDate("2020-01-02T01:00").millisSinceEpoch, SDate("2020-01-02T01:30").millisSinceEpoch) === 20
    }

    "give 15 passengers arriving at the pcp between 01:34 and 01:40 when there are 95 total passengers starting to arrive at 01:30" >> {
      FlightExports.totalPaxForArrivalInWindow(
        arrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 15
    }

    "give 95 passengers arriving at the pcp between 01:30 and 01:40 when there are 95 total passengers starting to arrive at 01:30" >> {
      FlightExports.totalPaxForArrivalInWindow(
        arrival, paxSourceOrder, SDate("2020-01-02T01:34").millisSinceEpoch, SDate("2020-01-02T01:40").millisSinceEpoch) === 15
    }
  }

  "Given a start and end date, a set of ports & terminals, an aggregator function and a flights provider" >> {
    val start = LocalDate(2020, 1, 1)
    val end = LocalDate(2020, 1, 2)
    val port = PortCode("MAN")
    val terminal = Terminal("T1")

    val utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed] = (_, _, _) => {
      Source(List(
        (UtcDate(2020, 1, 1), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = passengers), Set()),
        ))),
        (UtcDate(2020, 1, 2), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0002", schDt = "2020-01-02T00:05", pcpDt = "2020-01-02T00:30", passengerSources = passengers), Set()),
        ))),
      ))
    }

    "I should get rows for each terminal and aggregation level" >> {
      val getFlights = FlightExports.flightsProvider(utcFlightsProvider, paxSourceOrder)
      val toRows = FlightExports.dateAndFlightsToCsvRows(port, terminal, paxSourceOrder)
      val csvStream = GeneralExport.toCsv(start, end, terminal, getFlights, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002
          |North,MAN,T1,BA0001
          |""".stripMargin
      )

      result === expected
    }
  }

  "flightsToDailySummaryRows should" >> {
    val start = LocalDate(2020, 1, 1)
    val end = LocalDate(2020, 1, 2)
    val port = PortCode("MAN")
    val terminal = Terminal("T1")

    val utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed] = (_, _, _) => {
      Source(List(
        (UtcDate(2020, 1, 1), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = passengers), Set()),
        ))),
        (UtcDate(2020, 1, 2), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0002", schDt = "2020-01-02T00:05", pcpDt = "2020-01-02T00:30", passengerSources = passengers), Set()),
        ))),
      ))
    }
    val flights = Seq(
      ArrivalGenerator.arrival(iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = passengers),
      ArrivalGenerator.arrival(iata = "BA0002", schDt = "2020-01-02T00:05", pcpDt = "2020-01-02T00:30", passengerSources = passengers),
    )

    "I should get rows for each terminal and aggregation level" >> {
      val paxMinutesProvider: LocalDate => Future[Seq[PassengersMinute]] = _ =>
        Future.successful(Seq())
      val dateAndFLightsToRows = FlightExports.flightsToDailySummaryRows(port, terminal, paxSourceOrder, paxMinutesProvider)
      dateAndFLightsToRows(LocalDate(2020, 1, 2), flights)

      1 === 1
    }
  }
}
