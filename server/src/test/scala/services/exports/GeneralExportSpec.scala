package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import drt.shared.CrunchApi.PassengersMinute
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.DurationInt

class GeneralExportSpec extends CrunchTestLike {
  val paxSourceOrder: List[FeedSource] = List(LiveFeedSource)

  def passengers(maybeActualPax: Option[Int], maybeTransPax: Option[Int]): Map[FeedSource, Passengers] =
    Map[FeedSource, Passengers](LiveFeedSource -> Passengers(maybeActualPax, maybeTransPax))

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
    val arrival = ArrivalGenerator.arrival(
      iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None))

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

  "toCsv should give a row for each flight relevant to the date range, including the region, port and terminal" >> {
    val start = LocalDate(2020, 1, 1)
    val end = LocalDate(2020, 1, 2)
    val port = PortCode("MAN")
    val terminal = Terminal("T1")

    val utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, FlightsWithSplits), NotUsed] = (_, _, _) =>
      Source(List(
        (UtcDate(2020, 1, 1), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None)), Set()),
        ))),
        (UtcDate(2020, 1, 2), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0002", schDt = "2020-01-02T00:05", pcpDt = "2020-01-02T00:30", passengerSources = passengers(Option(95), None)), Set()),
        ))),
        (UtcDate(2020, 1, 3), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0003", schDt = "2020-01-03T02:05", pcpDt = "2020-01-02T23:55", passengerSources = passengers(Option(95), None)), Set()),
        ))),
        (UtcDate(2020, 1, 4), FlightsWithSplits(Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0004", schDt = "2020-01-03T02:30", pcpDt = "2020-01-03T01:55", passengerSources = passengers(Option(95), None)), Set()),
        ))),
      ))

    "Given a flights provider, and dateAndFlightsToCsvRows as an aggregator" >> {
      val getFlights = FlightExports.flightsProvider(utcFlightsProvider, paxSourceOrder)
      val toRows = FlightExports.dateAndFlightsToCsvRows(port, terminal, paxSourceOrder)
      val csvStream = GeneralExport.toCsv(start, end, terminal, getFlights, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002,BA0002,JFK,/,Scheduled,2020-01-02 00:05,,,,,,,2020-01-02 00:30,95
          |North,MAN,T1,BA0001,BA0001,JFK,/,Scheduled,2020-01-01 20:00,,,,,,,2020-01-02 01:30,95
          |North,MAN,T1,BA0003,BA0003,JFK,/,Scheduled,2020-01-03 02:05,,,,,,,2020-01-02 23:55,95
          |""".stripMargin
      )

      result === expected
    }
  }

  "flightsToDailySummaryRow should" >> {
    val port = PortCode("MAN")
    val terminal = Terminal("T1")

    val pax = 95
    val flights = Seq(
      ArrivalGenerator.arrival(iata = "BA0001", schDt = "2020-01-01T20:00", pcpDt = "2020-01-02T23:57", passengerSources = passengers(Option(pax), None)),
      ArrivalGenerator.arrival(iata = "BA0002", schDt = "2020-01-02T00:05", pcpDt = "2020-01-01T23:59", passengerSources = passengers(Option(pax), None)),
    )

    "I should get a single row with total pax from flights and breakdowns from the pax minutes" >> {
      val date = LocalDate(2020, 1, 2)
      val paxMinutesProvider: (LocalDate, Terminal) => Future[Seq[PassengersMinute]] = (_, _) =>
        Future.successful(Seq(
          PassengersMinute(T1, EeaDesk, SDate(date).addMinutes(30).millisSinceEpoch, Seq.fill(5)(0d), None),
          PassengersMinute(T1, EeaDesk, SDate(date).addMinutes(31).millisSinceEpoch, Seq.fill(6)(0d), None),
          PassengersMinute(T1, EGate, SDate(date).addMinutes(45).millisSinceEpoch, Seq.fill(7)(0d), None),
          PassengersMinute(T1, EGate, SDate(date).addMinutes(46).millisSinceEpoch, Seq.fill(8)(0d), None),
          PassengersMinute(T1, NonEeaDesk, SDate(date).addMinutes(55).millisSinceEpoch, Seq.fill(9)(0d), None),
          PassengersMinute(T1, NonEeaDesk, SDate(date).addMinutes(56).millisSinceEpoch, Seq.fill(10)(0d), None),
        ))
      val dateAndFLightsToRows = FlightExports.flightsToDailySummaryRow(port, terminal, paxSourceOrder, paxMinutesProvider)
      val csv = Await.result(dateAndFLightsToRows(date, flights), 1.second).mkString

      val ba0001PaxDuringWindow = 3 * 20
      val ba0002PaxDuringWindow = pax - 20
      val totalPax = ba0002PaxDuringWindow + ba0001PaxDuringWindow
      val pcpPax = 5 + 6 + 7 + 8 + 9 + 10
      val transPax = totalPax - pcpPax
      val queuePax = 0
      val egatePax = 7 + 8
      val eeaPax = 5 + 6
      val nonEeaPax = 9 + 10
      val fastTrackPax = 0

      csv ===
        s"""2020-01-02,North,MAN,T1,$totalPax,$pcpPax,$transPax,$queuePax,$egatePax,$eeaPax,$nonEeaPax,$fastTrackPax
           |""".stripMargin
    }
  }
}
