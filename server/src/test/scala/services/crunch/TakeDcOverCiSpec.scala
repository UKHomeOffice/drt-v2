//package services.crunch
//
//import akka.pattern.AskableActorRef
//import akka.testkit.TestProbe
//import controllers.ArrivalGenerator
//import drt.shared.FlightsApi.Flights
//import drt.shared.Queues
//import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
//import passengersplits.parsing.VoyageManifestParser.VoyageManifest
//import services.Crunch.{CrunchState, getLocalLastMidnight}
//import drt.shared.PaxTypesAndQueues._
//import services.SDate
//
//
//class TakeDcOverCiSpec extends CrunchTestLike {
//  isolated
//  sequential
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
//    val runnableGraphDispatcher: (List[Flights], List[VoyageManifests]) => AskableActorRef =
//      runCrunchGraph(
//        procTimes = procTimes,
//        testProbe = testProbe,
//        crunchStartDateProvider = () => SDate(scheduled).millisSinceEpoch
//      )
//
//    runnableGraphDispatcher(flights, Nil)
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
//}