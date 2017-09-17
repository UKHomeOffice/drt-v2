package feeds

//import actors.FlightPaxNumbers
import akka.NotUsed
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{Specification, SpecificationLike}
import drt.shared.Arrival
import drt.shared.FlightsApi.Flights
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import drt.server.feeds.lhr.{LHRFlightFeed, LHRLiveFlight}
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}
import org.slf4j.LoggerFactory
import services.inputfeeds.TestCrunchConfig.TestContext
import spray.http.DateTime

import scala.collection.generic.SeqFactory
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import sys.process._
import scala.concurrent.duration._

class LHRFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {

  "lhrCsvToApiFlights" should {
    "Produce an Arrival source with one flight based on a line from the LHR csv" in {
      //
      val csvString =
        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
           |"4","QR005","Qatar Airways","DOH","Doha","22:00 09/03/2017","21:32 09/03/2017","21:33 09/03/2017","21:43 09/03/2017","21:45 09/03/2017","10","795","142","1""""
          .stripMargin
      import system.dispatcher
      import akka.pattern.pipe

      implicit val materializer = ActorMaterializer()
      val csvGetters: Iterator[(Int) => String] = LHRFlightFeed.csvParserAsIteratorOfColumnGetter(csvString)
      val lhrFeed = LHRFlightFeed(csvGetters)

      val probe = TestProbe()

      val flightsSource: Source[List[Arrival], NotUsed] = lhrFeed.copiedToApiFlights

      val futureFlightsSeq: Future[Seq[List[Arrival]]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3 seconds)

      flights match {
        case Vector(
        Arrival(
        "Qatar Airways",
        "UNK",
        "2017-03-09T21:32:00.000Z",
        "2017-03-09T21:33:00.000Z",
        "2017-03-09T21:43:00.000Z",
        "2017-03-09T21:45:00.000Z",
        "", "10", 795, 142, 1, "", "", _, "LHR", "T4", "QR005", "QR005", "DOH",
        "2017-03-09T22:00:00.000Z",
        0L,
        1489097040000L,
        None) :: tail) =>
          true
        case _ =>
          false
      }
    }

    "Produce an Arrival source with one flight based on a line with missing values from the LHR csv" in {
      val csvString =
        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
           |"4","KL1033","KLM Royal Dutch Airlines","AMS","Amsterdam","20:50 09/03/2017","20:50 09/03/2017","","","","","","","""""
          .stripMargin
      import system.dispatcher
      import akka.pattern.pipe

      implicit val materializer = ActorMaterializer()

      val csv: CSVParser = CSVParser.parse(csvString, CSVFormat.DEFAULT)
      val csvGetters: Iterator[(Int) => String] = csv.iterator().asScala.map((l: CSVRecord) => (i: Int) => l.get(i))
      val lhrFeed = LHRFlightFeed(csvGetters)


      val probe = TestProbe()
      val flightsSource: Source[List[Arrival], NotUsed] = lhrFeed.copiedToApiFlights
      val futureFlightsSeq: Future[Seq[List[Arrival]]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3 seconds)

      flights match {
        case Vector(List(flight: Arrival)) =>
          true
        case _ =>
          false
      }
    }

    "should consistently return the same flightid for the same flight" in {
      val flightv1 = LHRLiveFlight("T1", "SA123", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)
      val flightv2 = LHRLiveFlight("T1", "SA123", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)

      flightv1.flightId() === flightv2.flightId()
    }

    "should not return the same flightid for different flights" in {
      val flightv1 = LHRLiveFlight("T1", "SA324", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)
      val flightv2 = LHRLiveFlight("T1", "SA123", "SAA", "JHB", "LHR", org.joda.time.DateTime.parse("2017-01-01T20:00:00z"), None, None, None, None, None, None, None, None)

      flightv1.flightId() !== flightv2.flightId()
    }

    //  TODO: We need to figure out how to make this test reliably pass.
    // "Produce an Arrival with scheduled datetime in UTC when given a flight with a date falling inside BST" in {
    //      //
    //      val csvString =
    //        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
    //           |"4","QR005","Qatar Airways","DOH","Doha","22:00 09/04/2017","21:32 09/04/2017","21:33 09/04/2017","21:43 09/04/2017","21:45 09/04/2017","10","795","142","1""""
    //          .stripMargin
    //      import system.dispatcher
    //      import akka.pattern.pipe
    //
    //      implicit val materializer = ActorMaterializer()
    //      val csvGetters: Iterator[(Int) => String] = LHRFlightFeed.csvParserAsIteratorOfColumnGetter(csvString)
    //      val lhrFeed = LHRFlightFeed(csvGetters)
    //
    //      val probe = TestProbe()
    //
    //      val flightsSource: Source[List[Arrival], NotUsed] = lhrFeed.copiedToApiFlights
    //
    //      val futureFlightsSeq: Future[Seq[List[Arrival]]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)
    //
    //      val flights: Seq[List[Arrival]] = Await.result(futureFlightsSeq, 3 seconds)
    //
    //      flights match {
    //        case Vector(Arrival(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, "2017-04-09T22:00:00.000Z", _) :: tail) =>
    //          true
    //        case Vector(Arrival(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, scheduled, _) :: tail) =>
    //          println(s"CHECK JVM TIMEZONE SETTING!! SchDT: $scheduled != '2017-04-09T22:00:00.000Z'")
    //          false
    //        case _ => false
    //      }
    //    }
  }
}


