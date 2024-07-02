package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class FlightExportsSpec extends CrunchTestLike {
  val paxSourceOrder: List[FeedSource] = List(LiveFeedSource)

  def passengers(maybeActualPax: Option[Int], maybeTransPax: Option[Int]): Map[FeedSource, Passengers] =
    Map[FeedSource, Passengers](LiveFeedSource -> Passengers(maybeActualPax, maybeTransPax))

  "toCsv should give a row for each flight relevant to the date range, including the region, port and terminal" >> {
    val start = LocalDate(2020, 6, 1)
    val end = LocalDate(2020, 6, 2)
    val port = PortCode("MAN")
    val terminal = Terminal("T1")

    val utcFlightsProvider: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = (_, _) =>
      Source(List(
        (UtcDate(2020, 6, 1), Seq(
          ApiFlightWithSplits(ArrivalGenerator.live(
            iata = "BA0001", schDt = "2020-06-01T20:00", totalPax = Option(95), maxPax = Option(100)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-06-02T01:30").millisSinceEpoch)), Set()),
        )),
        (UtcDate(2020, 6, 2), Seq(
          ApiFlightWithSplits(ArrivalGenerator.live(
            iata = "BA0002", schDt = "2020-06-02T00:05", totalPax = Option(95), maxPax = Option(100)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-06-02T00:30").millisSinceEpoch)), Set()),
        )),
        (UtcDate(2020, 6, 3), Seq(
          ApiFlightWithSplits(ArrivalGenerator.live(
            iata = "BA0003", schDt = "2020-06-03T00:05", totalPax = Option(95), maxPax = Option(100)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-06-02T22:55").millisSinceEpoch)), Set()),
        )),
        (UtcDate(2020, 6, 4), Seq(
          ApiFlightWithSplits(ArrivalGenerator.live(
            iata = "BA0004", schDt = "2020-06-03T02:30", totalPax = Option(95), maxPax = Option(100)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-06-03T01:55").millisSinceEpoch)), Set()),
        )),
      ))

    "Given a flights provider, and dateAndFlightsToCsvRows as an aggregator, when the range is a single day" >> {
      val getFlights = FlightExports.flightsForLocalDateRangeProvider(utcFlightsProvider, paxFeedSourceOrder)
      val toRows = FlightExports.dateAndFlightsToCsvRows(port, terminal, paxSourceOrder, _ => Future.successful(VoyageManifests.empty))
      val csvStream = GeneralExport.toCsv(end, end, getFlights, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002,BA0002,JFK,/,Scheduled,2020-06-02 01:05,,,,,,,2020-06-02 01:30,100,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0001,BA0001,JFK,/,Scheduled,2020-06-01 21:00,,,,,,,2020-06-02 02:30,100,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0003,BA0003,JFK,/,Scheduled,2020-06-03 01:05,,,,,,,2020-06-02 23:55,100,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |""".stripMargin
      )

      result === expected
    }

    "Given a flights provider, and dateAndFlightsToCsvRows as an aggregator" >> {
      val getFlights = FlightExports.flightsForLocalDateRangeProvider(utcFlightsProvider, paxFeedSourceOrder)
      val toRows = FlightExports.dateAndFlightsToCsvRows(port, terminal, paxSourceOrder, _ => Future.successful(VoyageManifests.empty))
      val csvStream = GeneralExport.toCsv(start, end, getFlights, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002,BA0002,JFK,/,Scheduled,2020-06-02 01:05,,,,,,,2020-06-02 01:30,100,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0001,BA0001,JFK,/,Scheduled,2020-06-01 21:00,,,,,,,2020-06-02 02:30,100,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0003,BA0003,JFK,/,Scheduled,2020-06-03 01:05,,,,,,,2020-06-02 23:55,100,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |""".stripMargin
      )

      result === expected
    }
  }

  val arrival: Arrival = ArrivalGenerator.live(
    iata = "BA0001", schDt = "2020-01-01T20:05", totalPax = Option(95), origin = PortCode("JER")).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-01-02T01:30").millisSinceEpoch))
  val ctaArrival: Arrival = ArrivalGenerator.live(
    iata = "BA0002", schDt = "2020-01-01T20:10", totalPax = Option(95), origin = PortCode("LHR")).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-01-02T01:30").millisSinceEpoch))
  val domesticArrival: Arrival = ArrivalGenerator.live(
    iata = "BA0003", schDt = "2020-01-01T20:15", totalPax = Option(95)).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-01-02T01:30").millisSinceEpoch))
  val cancelledArrival: Arrival = ArrivalGenerator.live(
    iata = "BA0004", schDt = "2020-01-01T20:20", totalPax = Option(95), status = ArrivalStatus("cancelled")).toArrival(LiveFeedSource).copy(PcpTime = Option(SDate("2020-01-02T01:30").millisSinceEpoch))
}
