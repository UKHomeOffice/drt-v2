package services.crunch

import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.Queues
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import services.Crunch.CrunchState
import services.SDate


class VoyageManifestsSpec extends CrunchTestLike {
  isolated
  sequential

  "Given a VoyageManifest emitted from the VM source before the corresponding flight is emitted " +
    "When I crunch the flight " +
    "Then I should see the VoyageManifest split with the flight" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flights = List(Flights(List(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
    )))
    val manifests = List(VoyageManifests(Set(
      VoyageManifest("CI", "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
        PassengerInfoJson(Some("P"), "GBR", "", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"))
      ))
    )))

    val testProbe = TestProbe()
    val runnableGraphDispatcher: (Source[Flights, _], Source[VoyageManifests, _]) => AskableActorRef =
      runCrunchGraph(
        procTimes = procTimes,
        testProbe = testProbe,
        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
      )

    runnableGraphDispatcher(Source(flights), Source(manifests))

    val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
    val resultSummary = paxLoadsFromCrunchState(result, 2)

    val expected = Map("T1" -> Map(
      Queues.EeaDesk -> Seq(20 * 0.5, 1 * 0.5),
      Queues.EGate -> Seq(20 * 0.5, 1 * 0.5)
    ))

    resultSummary === expected
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
  //    val runnableGraphDispatcher: (Source[Flights, _], Source[VoyageManifests, _]) => AskableActorRef =
  //      runCrunchGraph(
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