package services.exports

import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import controllers.ArrivalGenerator
import passengersplits.parsing.VoyageManifestParser.VoyageManifests
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{LocalDate, UtcDate}

import scala.collection.immutable.Seq
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

    val utcFlightsProvider: (UtcDate, UtcDate, Terminal) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = (_, _, _) =>
      Source(List(
        (UtcDate(2020, 6, 1), Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0001", schDt = "2020-06-01T20:00", pcpDt = "2020-06-02T01:30", passengerSources = passengers(Option(95), None)), Set()),
        )),
        (UtcDate(2020, 6, 2), Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0002", schDt = "2020-06-02T00:05", pcpDt = "2020-06-02T00:30", passengerSources = passengers(Option(95), None)), Set()),
        )),
        (UtcDate(2020, 6, 3), Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0003", schDt = "2020-06-03T00:05", pcpDt = "2020-06-02T22:55", passengerSources = passengers(Option(95), None)), Set()),
        )),
        (UtcDate(2020, 6, 4), Seq(
          ApiFlightWithSplits(ArrivalGenerator.arrival(
            iata = "BA0004", schDt = "2020-06-03T02:30", pcpDt = "2020-06-03T01:55", passengerSources = passengers(Option(95), None)), Set()),
        )),
      ))

    "Given a flights provider, and dateAndFlightsToCsvRows as an aggregator, when the range is a single day" >> {
      val getFlights = FlightExports.flightsProvider(utcFlightsProvider)
      val toRows = FlightExports.dateAndFlightsToCsvRows(port, terminal, paxSourceOrder, (_, _) => Future.successful(VoyageManifests.empty))
      val csvStream = GeneralExport.toCsv(end, end, terminal, getFlights, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002,BA0002,JFK,/,Scheduled,2020-06-02 01:05,,,,,,,2020-06-02 01:30,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0001,BA0001,JFK,/,Scheduled,2020-06-01 21:00,,,,,,,2020-06-02 02:30,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0003,BA0003,JFK,/,Scheduled,2020-06-03 01:05,,,,,,,2020-06-02 23:55,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |""".stripMargin
      )

      result === expected
    }

    "Given a flights provider, and dateAndFlightsToCsvRows as an aggregator" >> {
      val getFlights = FlightExports.flightsProvider(utcFlightsProvider)
      val toRows = FlightExports.dateAndFlightsToCsvRows(port, terminal, paxSourceOrder, (_, _) => Future.successful(VoyageManifests.empty))
      val csvStream = GeneralExport.toCsv(start, end, terminal, getFlights, toRows)

      val result = Await.result(csvStream.runWith(Sink.seq), 1.second)
      val expected = List(
        """North,MAN,T1,BA0002,BA0002,JFK,/,Scheduled,2020-06-02 01:05,,,,,,,2020-06-02 01:30,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0001,BA0001,JFK,/,Scheduled,2020-06-01 21:00,,,,,,,2020-06-02 02:30,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |North,MAN,T1,BA0003,BA0003,JFK,/,Scheduled,2020-06-03 01:05,,,,,,,2020-06-02 23:55,95,95,,,,,,,,,,,,,,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,"",""
          |""".stripMargin
      )

      result === expected
    }
  }

  val arrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0001", schDt = "2020-01-01T20:05", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None))
  val ctaArrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0002", schDt = "2020-01-01T20:10", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None), origin = PortCode("JER"))
  val domesticArrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0003", schDt = "2020-01-01T20:15", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None), origin = PortCode("LHR"))
  val cancelledArrival: Arrival = ArrivalGenerator.arrival(
    iata = "BA0004", schDt = "2020-01-01T20:20", pcpDt = "2020-01-02T01:30", passengerSources = passengers(Option(95), None), status = ArrivalStatus("cancelled"))
}
