package feeds

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import org.specs2.mutable.{Specification, SpecificationLike}
import spatutorial.shared.ApiFlight
import spatutorial.shared.FlightsApi.Flights
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import drt.server.feeds.lhr.LHRFlightFeed
import org.apache.commons.csv.{CSVFormat, CSVParser, CSVRecord}

import scala.collection.generic.SeqFactory
import scala.collection.JavaConverters._
import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import sys.process._
import scala.concurrent.duration._

class LHRFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {
  //  val username = ConfigFactory.load.getString("lhr_live_username")
  //  val password = ConfigFactory.load.getString("lhr_live_password")
  //
  //    "Executing the LHR feed script" should {
  //      "get us some content" in {
  //        val csvContents = Seq("/usr/local/bin/lhr-live-fetch-latest-feed.sh", "-u", username, "-p", password).!!
  //println(csvContents)
  //        csvContents.length > 0
  //      }
  //    }

  "lhrCsvToApiFlights" should {
    "Produce an ApiFlight source with one flight based on a line from the LHR csv" in {
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

      val flightsSource: Source[List[ApiFlight], NotUsed] = lhrFeed.copiedToApiFlights

      val futureFlightsSeq: Future[Seq[List[ApiFlight]]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3 seconds)

      flights match {
        case Vector(ApiFlight("Qatar Airways", "UNK", "2017-03-09T21:32:00.000Z", "2017-03-09T21:33:00.000Z", "2017-03-09T21:43:00.000Z", "2017-03-09T21:45:00.000Z", "", "10", 795, 142, 1, "", "", _, "LHR", "T4", "QR005", "QR005", "DOH", "2017-03-09T22:00:00.000Z", 1489097040000L) :: tail) =>
          true
        case _ =>
          false
      }
    }

    "Produce an ApiFlight source with one flight based on a line with missing values from the LHR csv" in {
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
      val flightsSource: Source[List[ApiFlight], NotUsed] = lhrFeed.copiedToApiFlights
      val futureFlightsSeq: Future[Seq[List[ApiFlight]]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)

      val flights = Await.result(futureFlightsSeq, 3 seconds)

      flights match {
        case Vector(List(flight: ApiFlight)) =>
          true
        case _ =>
          false
      }
    }
  }


}


