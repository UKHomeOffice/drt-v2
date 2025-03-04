package feeds.lhr

import akka.pattern.pipe
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.TestProbe
import drt.server.feeds.lhr.{LHRFlightFeed, LHRLiveFlight}
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T4}
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.jdk.CollectionConverters.IteratorHasAsScala

class LHRFeedSpec extends CrunchTestLike {
  "lhrCsvToApiFlights" should {
    "Produce an Arrival source with one flight based on a line from the LHR csv" in {
      val csvString =
        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
           |"4","QR005","Qatar Airways","DOH","Doha","22:00 09/03/2017","21:32 09/03/2017","21:33 09/03/2017","21:43 09/03/2017","21:45 09/03/2017","10","795","142","1""""
          .stripMargin

      val csvGetters: Iterator[Int => String] = LHRFlightFeed.csvParserAsIteratorOfColumnGetter(csvString)
      val lhrFeed = LHRFlightFeed(csvGetters)

      val probe = TestProbe()

      val flightsSource = Source(List(lhrFeed.copiedToApiFlights))

      val futureFlightsSeq = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3.seconds).asInstanceOf[Vector[LiveArrival]]

      flights.toList === List(
        List(
          LiveArrival(
            operator = Option("Qatar Airways"),
            totalPax = Option(142),
            transPax = Option(1),
            terminal = T4,
            voyageNumber = 5,
            carrierCode = "QR",
            flightCodeSuffix = None,
            origin = "DOH",
            previousPort = None,
            scheduled = SDate("2017-03-09T22:00:00.000Z").millisSinceEpoch,
            estimated = Option(SDate("2017-03-09T21:32:00.000Z").millisSinceEpoch),
            touchdown = Option(SDate("2017-03-09T21:33:00.000Z").millisSinceEpoch),
            estimatedChox = Option(SDate("2017-03-09T21:43:00.000Z").millisSinceEpoch),
            actualChox = Option(SDate("2017-03-09T21:45:00.000Z").millisSinceEpoch),
            status = "",
            gate = None,
            stand = Option("10"),
            maxPax = Option(795),
            runway = None,
            baggageReclaim = None,
          )
        )
      )
    }

    "Should accept 0 as a valid Pax value for all Pax fields" in {
      val csvString =
        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
           |"4","QR005","Qatar Airways","DOH","Doha","22:00 09/03/2017","21:32 09/03/2017","21:33 09/03/2017","21:43 09/03/2017","21:45 09/03/2017","10","0","0","0""""
          .stripMargin

      val csvGetters: Iterator[Int => String] = LHRFlightFeed.csvParserAsIteratorOfColumnGetter(csvString)
      val lhrFeed = LHRFlightFeed(csvGetters)

      val probe = TestProbe()

      val flightsSource = Source(List(lhrFeed.copiedToApiFlights))

      val futureFlightsSeq = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3.seconds).asInstanceOf[Vector[Arrival]]

      flights.toList === List(
        List(
          LiveArrival(
            operator = Option("Qatar Airways"),
            totalPax = Option(0),
            transPax = Option(0),
            terminal = T4,
            voyageNumber = 5,
            carrierCode = "QR",
            flightCodeSuffix = None,
            origin = "DOH",
            previousPort = None,
            scheduled = SDate("2017-03-09T22:00:00.000Z").millisSinceEpoch,
            estimated = Option(SDate("2017-03-09T21:32:00.000Z").millisSinceEpoch),
            touchdown = Option(SDate("2017-03-09T21:33:00.000Z").millisSinceEpoch),
            estimatedChox = Option(SDate("2017-03-09T21:43:00.000Z").millisSinceEpoch),
            actualChox = Option(SDate("2017-03-09T21:45:00.000Z").millisSinceEpoch),
            status = "",
            gate = None,
            stand = Option("10"),
            maxPax = Option(0),
            runway = None,
            baggageReclaim = None,
          )
        )
      )
    }

    "Produce an Arrival source with one flight based on a line with missing values from the LHR csv" in {
      val csvString =
        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
           |"4","KL1033","KLM Royal Dutch Airlines","AMS","Amsterdam","20:50 09/03/2017","20:50 09/03/2017","","","","","","","""""
          .stripMargin

      val csv: CSVParser = CSVParser.parse(csvString, CSVFormat.DEFAULT)
      val csvGetters: Iterator[Int => String] = csv.iterator().asScala.map((l: CSVRecord) => (i: Int) => l.get(i))
      val lhrFeed = LHRFlightFeed(csvGetters)


      val probe = TestProbe()
      val flightsSource = Source(List(lhrFeed.copiedToApiFlights))
      val futureFlightsSeq = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3.seconds)

      flights match {
        case Vector(List(_: FeedArrival)) =>
          true
        case _ =>
          false
      }
    }

    "should consistently return the same flightid for the same flight" in {
      val flightV1 = LHRLiveFlight(T1, "SA123", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)
      val flightV2 = LHRLiveFlight(T1, "SA123", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)

      flightV1.flightId() === flightV2.flightId()
    }

    "should not return the same flightid for different flights" in {
      val flightv1 = LHRLiveFlight(T1, "SA324", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)
      val flightv2 = LHRLiveFlight(T1, "SA123", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)

      flightv1.flightId() !== flightv2.flightId()
    }
  }
}
