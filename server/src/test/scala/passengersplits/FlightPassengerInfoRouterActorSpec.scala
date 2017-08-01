import akka.actor.{ActorSystem, Props}
import akka.pattern._
import akka.stream.ActorMaterializer
import akka.testkit.TestKit
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import drt.shared.MilliDate
import drt.shared.PassengerSplits.{SplitsPaxTypeAndQueueCount, VoyagePaxSplits}
import drt.shared.PaxTypes.NonVisaNational
import org.joda.time.DateTimeZone
import org.specs2.mutable.SpecificationLike
import passengersplits.AkkaPersistTestConfig
import passengersplits.core.PassengerInfoRouterActor.{ReportVoyagePaxSplit, ReportVoyagePaxSplitBetween, VoyagesPaxSplits}
import passengersplits.core.AdvancedPassengerInfoActor
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfo, PassengerInfoJson, VoyageManifest}
import services.SDate

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._


class WhenReportingVoyageManifestsSpec extends TestKit(ActorSystem("AkkaStreamTestKitSpecificationLike", AkkaPersistTestConfig.inMemoryAkkaPersistConfig)) with SpecificationLike {
  isolated
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(1 second)

  "Given a AdvancedPassengerInfoActor " >> {

    "When we send it a CI VoyageManifest " +
      "Then the pax in the reported splits should match the pax in that CI VoyageManifest" >> {
      val flightPassengerSplitReporter = system.actorOf(Props[AdvancedPassengerInfoActor], name = "flight-pax-reporter")

      val civm = VoyageManifest(EventCodes.CheckIn, "LHR", "JFK", "0123", "BA", "2017-01-01", "00:00:00", List(
        PassengerInfoJson(None, "GB", "", None, None, "N", None, None)
      ))

      flightPassengerSplitReporter ! civm

      Thread.sleep(100L)

      val future = flightPassengerSplitReporter ? ReportVoyagePaxSplit("LHR", "BA", "0123", SDate("2017-01-01T00:00:00Z", DateTimeZone.UTC))

      val paxCount = Await.result(future, 1 second) match {
        case vm@VoyagePaxSplits(_, _, _, totalPaxCount, _, _) => totalPaxCount
        case f => -1
      }

      val expectedPaxCount = 1

      paxCount === expectedPaxCount
    }

    "When we send it a DC VoyageManifest " +
      "Then the pax in the reported splits should match the pax in that VoyageManifest" >> {
      val flightPassengerSplitReporter = system.actorOf(Props[AdvancedPassengerInfoActor], name = "flight-pax-reporter")

      val dcvm = VoyageManifest(EventCodes.DoorsClosed, "LHR", "JFK", "0123", "BA", "2017-01-01", "00:00:00", List(
        PassengerInfoJson(None, "GB", "", None, None, "N", None, None)
      ))

      flightPassengerSplitReporter ! dcvm

      Thread.sleep(100L)

      val future = flightPassengerSplitReporter ? ReportVoyagePaxSplit("LHR", "BA", "0123", SDate("2017-01-01T00:00:00Z", DateTimeZone.UTC))

      val paxCount = Await.result(future, 1 second) match {
        case vm@VoyagePaxSplits(_, _, _, totalPaxCount, _, _) => totalPaxCount
        case f => -1
      }

      val expectedPaxCount = 1

      paxCount === expectedPaxCount
    }

    "When we send it a CI VoyageManifest with 100 pax followed by a DC VoyageManifest with 150 pax (50% increase)" +
      "Then the pax in the reported splits should be 110 (matching DC)" >> {
      val ciPaxCount = 100
      val diPaxCount = 150

      val paxCount = sendCiThenDcAndReturnSplitsPaxCount(ciPaxCount, diPaxCount)

      val expectedPaxCount = diPaxCount

      paxCount === expectedPaxCount
    }

    "When we send it a CI VoyageManifest with 100 pax followed by a DC VoyageManifest with 151 pax (51% increase)" +
      "Then the pax in the reported splits should be 100 (matching DC)" >> {
      val ciPaxCount = 100
      val diPaxCount = 151

      val paxCount = sendCiThenDcAndReturnSplitsPaxCount(ciPaxCount, diPaxCount)

      val expectedPaxCount = ciPaxCount

      paxCount === expectedPaxCount
    }

    "When we ask for all splits in a time range at a port " +
      "Then we should recieve a list of those flights and their splits" >> {
      val flightPassengerSplitReporter = system.actorOf(Props[AdvancedPassengerInfoActor], name = "flight-pax-reporter")

      val manifest = VoyageManifest(EventCodes.CheckIn, "LHR", "JFK", "0123", "BA", "2017-01-01", "12:00:00", List(
        PassengerInfoJson(None, "GB", "", None, None, "N", None, None)
      ))

      flightPassengerSplitReporter ! manifest

      val future = flightPassengerSplitReporter ? ReportVoyagePaxSplitBetween(SDate(2017, 1, 1, 0, 0), SDate(2017, 1, 2, 0, 0))

      val expected = List(VoyageManifest("CI","LHR","JFK","0123","BA","2017-01-01","12:00:00",List(PassengerInfoJson(None,"GB","",None,None,"N",None,None))))
      val result = Await.result(future, 1 second)
      result === expected
    }
  }

  private def sendCiThenDcAndReturnSplitsPaxCount(ciPaxCount: Int, diPaxCount: Int): Int = {
    val portCode = "LHR"
    val flightNumber = "0123"
    val carrierCode = "BA"
    val schDate = "2017-01-01"
    val schTime = "00:00:00"

    val flightPassengerSplitReporter = system.actorOf(Props[AdvancedPassengerInfoActor], name = "flight-pax-reporter")

    val ciPax = List.fill(ciPaxCount)(PassengerInfoJson(None, "GB", "", None, None, "N", None, None))
    val diPax = List.fill(diPaxCount)(PassengerInfoJson(None, "GB", "", None, None, "N", None, None))
    val civm = VoyageManifest(EventCodes.CheckIn, portCode, "JFK", flightNumber, carrierCode, schDate, schTime, ciPax)
    val dcvm = VoyageManifest(EventCodes.DoorsClosed, portCode, "JFK", flightNumber, carrierCode, schDate, schTime, diPax)

    flightPassengerSplitReporter ! civm
    Thread.sleep(100L)

    flightPassengerSplitReporter ! dcvm
    Thread.sleep(100L)

    val future: Future[Any] = flightPassengerSplitReporter ? ReportVoyagePaxSplit(portCode, carrierCode, flightNumber, SDate(s"${schDate}T${schTime}Z", DateTimeZone.UTC))

    Await.result(future, 1 second).asInstanceOf[VoyagePaxSplits].totalPaxCount
  }
}
