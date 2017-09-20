package services.crunch

import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.Queues._
import drt.shared.SplitRatiosNs.SplitSources._
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import services.Crunch.CrunchState
import services.SDate

import scala.collection.immutable.Seq
import scala.concurrent.duration._


class VoyageManifestsSpec extends CrunchTestLike {
  isolated
  sequential

  "Given a VoyageManifest arriving before its corresponding flight " +
    "When I crunch the flight " +
    "Then I should see the flight with its VoyageManifest split" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flight = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    val flightsSource = Source.tick(1 seconds, 1 minute, Flights(List(flight)))
    val manifestsSource = Source.tick(1 millisecond, 1 minute, VoyageManifests(Set(
      VoyageManifest("CI", "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"))
      ))
    )))

    val testProbe = TestProbe()
    val runnableGraphDispatcher =
      runCrunchGraph[Cancellable](
        procTimes = Map(
          eeaMachineReadableToDesk -> 25d / 60,
          eeaMachineReadableToEGate -> 25d / 60
        ),
        queues = Map("T1" -> Seq(EeaDesk, EGate)),
        testProbe = testProbe,
        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
      ) _

    val (fs, ms, _) = runnableGraphDispatcher(flightsSource, manifestsSource)

    val flights = testProbe.expectMsgAnyClassOf(classOf[CrunchState]) match {
      case CrunchState(_, _, f, _) => f
    }
    ms.cancel
    fs.cancel

    val expectedSplits = Set(
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 100.0)), TerminalAverage, Percentage),
      ApiSplits(Set(
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EGate, 1.0),
        ApiPaxTypeAndQueueCount(EeaMachineReadable, EeaDesk, 0.0)), ApiSplitsWithCsvPercentage, PaxNumbers)
    )
    val splitsSet = flights.head match {
      case ApiFlightWithSplits(_, s) => s
    }

    splitsSet === expectedSplits
  }

  //  "Given 2 DQ messages for a flight, where the DC message arrives after the CI message " +
  //    "When I crunch the flight " +
  //    "Then I should see the DQ manifest was used" >> {
  //
  //    val scheduled = "2017-01-01T00:00Z"
  //
  //    val flights = List(Flights(List(
  //      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
  //    )))
  //
  //    val testProbe = TestProbe()
  //    val runnableGraphDispatcher =
  //      runCrunchGraph[NotUsed](
  //        procTimes = procTimes,
  //        testProbe = testProbe,
  //        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
  //      )
  //
  //    runnableGraphDispatcher(Source(flights), Source(List()))
  //
  //    val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
  //    val resultSummary = paxLoadsFromCrunchState(result, 2)
  //
  //    val expected = Map("T1" -> Map(
  //      Queues.EeaDesk -> Seq(20 * edSplit, 1 * edSplit),
  //      Queues.EGate -> Seq(20 * egSplit, 1 * egSplit)
  //    ))
  //
  //    resultSummary === expected
  //  }
}