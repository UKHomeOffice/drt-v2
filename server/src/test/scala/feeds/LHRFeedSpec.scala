package feeds

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Sink, Source}
import akka.testkit.{TestKit, TestProbe}
import com.typesafe.config.ConfigFactory
import controllers.LHRFlightFeed
import org.specs2.mutable.{Specification, SpecificationLike}
import spatutorial.shared.ApiFlight
import spatutorial.shared.FlightsApi.Flights
import akka.pattern.pipe
import akka.stream.ActorMaterializer

import scala.collection.generic.SeqFactory
import scala.collection.immutable.Seq
import scala.concurrent.{Await, Future}
import sys.process._
import scala.concurrent.duration._

class LHRFeedSpec extends TestKit(ActorSystem("testActorSystem", ConfigFactory.empty())) with SpecificationLike {
  val username = ConfigFactory.load.getString("lhr_live_username")
  val password = ConfigFactory.load.getString("lhr_live_password")

    "Executing the LHR feed script" should {
      "get us some content" in {
        val csvContents = Seq("/usr/local/bin/lhr-live-fetch-latest-feed.sh", "-u", username, "-p", password).!!
println(csvContents)
        csvContents.length > 0
      }
    }

//  "lhrCsvToApiFlights" should {
//    "Produce an ApiFlight source with one flight based on a line from the LHR csv" in {
//      //
//      val csvString =
//        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
//           |"3","RJ111","Royal Jordanian","AMM","Amman","14:45 06/03/2017","14:46 06/03/2017","14:45 06/03/2017","14:50 06/03/2017","15:05 06/03/2017","303R","162","124","33""""
//          .stripMargin
//      import system.dispatcher
//      import akka.pattern.pipe
//
//      implicit val materializer = ActorMaterializer()
//
//      val lhrFeed = LHRFlightFeed(csvString)
//
//      val probe = TestProbe()
//
//      val flightsSource: Source[Flights, NotUsed] = lhrFeed.copiedToApiFlights
//
//      val futureFlightsSeq: Future[Seq[Flights]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)
//
//      val flights = Await.result(futureFlightsSeq, 3 seconds)
//
//      flights match {
//        case Vector(
//        Flights(
//        List(
//        ApiFlight("Royal Jordanian", "UNK", "14:46 06/03/2017", "14:45 06/03/2017", "14:50 06/03/2017", "15:05 06/03/2017",
//        "", "303R", 162, 124, 33, "", "", _, "LHR", "3", "RJ111", "RJ111", "Amman", "2017-03-06T14:45:00.000Z", 1488811740000L)
//        ))) =>
//          true
//        case _ => false
//      }
//    }
//
//    "Produce an ApiFlight source with two flights based on two lines from the LHR csv" in {
//      //
//      val csvString =
//        """|Term","Flight No","Operator","From","Airport name","Scheduled","Estimated","Touchdown","Est Chocks","Act Chocks","Stand","Max pax","Act Pax","Conn pax"
//           |"3","RJ111","Royal Jordanian","AMM","Amman","14:45 06/03/2017","14:46 06/03/2017","14:45 06/03/2017","14:50 06/03/2017","15:05 06/03/2017","303R","162","124","33"
//           |"3","RJ111","Another Airline","AMM","Amman","14:45 06/03/2017","14:46 06/03/2017","14:45 06/03/2017","14:50 06/03/2017","15:05 06/03/2017","303R","162","124","33""""
//          .stripMargin
//      import system.dispatcher
//      import akka.pattern.pipe
//
//      implicit val materializer = ActorMaterializer()
//
//      val lhrFeed = LHRFlightFeed(csvString)
//
//      val probe = TestProbe()
//
//      val flightsSource: Source[Flights, NotUsed] = lhrFeed.copiedToApiFlights
//
//      val futureFlightsSeq: Future[Seq[Flights]] = flightsSource.runWith(Sink.seq).pipeTo(probe.ref)
//
//      val flights = Await.result(futureFlightsSeq, 3 seconds)
//
//      flights match {
//        case Vector(
//        Flights(
//        List(
//        ApiFlight("Royal Jordanian", "UNK", "14:46 06/03/2017", "14:45 06/03/2017", "14:50 06/03/2017", "15:05 06/03/2017",
//        "", "303R", 162, 124, 33, "", "", _, "LHR", "3", "RJ111", "RJ111", "Amman", "2017-03-06T14:45:00.000Z", 1488811740000L),
//        ApiFlight("Another Airline", "UNK", "14:46 06/03/2017", "14:45 06/03/2017", "14:50 06/03/2017", "15:05 06/03/2017",
//        "", "303R", 162, 124, 33, "", "", _, "LHR", "3", "RJ111", "RJ111", "Amman", "2017-03-06T14:45:00.000Z", 1488811740000L)
//        ))) =>
//          true
//        case _ => false
//      }
//    }
//  }
}


