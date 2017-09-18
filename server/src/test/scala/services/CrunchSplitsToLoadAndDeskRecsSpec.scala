package services

import akka.NotUsed
import akka.actor.ActorRef
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate}
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.joda.time.DateTimeZone
import passengersplits.parsing.VoyageManifestParser.{EventCodes, PassengerInfoJson, VoyageManifest}
import services.Crunch.{CrunchState, getLocalLastMidnight}

import scala.collection.immutable.{List, Seq}


class CrunchSplitsToLoadAndDeskRecsSpec extends CrunchTestLike {
  isolated
  sequential

  "Crunch split workload flow " >> {
    "Given a flight with 21 passengers and splits to eea desk & egates " +
      "When I ask for queue loads " +
      "Then I should see 4 queue loads, 2 for the first 20 pax to each queue and 2 for the last 1 split to each queue" >> {

      val scheduled = "2017-01-01T00:00Z"
      val edSplit = 0.25
      val egSplit = 0.75

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 21)
      )))

      val procTimes: Map[PaxTypeAndQueue, Double] = Map(
        eeaMachineReadableToDesk -> 20d / 60,
        eeaMachineReadableToEGate -> 35d / 60)

      val testProbe = TestProbe()
      val runnableGraphDispatcher: (List[Flights], List[Set[VoyageManifest]]) => AskableActorRef =
        runCrunchGraph(
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch,
          portSplits = SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, edSplit),
            SplitRatio(eeaMachineReadableToEGate, egSplit)
          )
        )

      runnableGraphDispatcher(flights, Nil)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 2)

      val expected = Map("T1" -> Map(
        Queues.EeaDesk -> Seq(20 * edSplit, 1 * edSplit),
        Queues.EGate -> Seq(20 * egSplit, 1 * egSplit)
      ))

      resultSummary === expected
    }

    "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
      "When I ask for queue loads " +
      "Then I should see two eea desk queue loads containing the 2 passengers and their proc time" >> {
      val scheduled1 = "2017-01-01T00:00Z"
      val scheduled2 = "2017-01-01T00:01Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1, iata = "BA0001", terminal = "T1", actPax = 1),
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled2, iata = "SA123", terminal = "T1", actPax = 1)
      )))

      val procTimes: Map[PaxTypeAndQueue, Double] = Map(
        eeaMachineReadableToDesk -> 20d / 60,
        eeaMachineReadableToEGate -> 35d / 60)

      val testProbe = TestProbe()
      val runnableGraphDispatcher: (List[Flights], List[Set[VoyageManifest]]) => AskableActorRef =
        runCrunchGraph(
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled1)).millisSinceEpoch
        )

      runnableGraphDispatcher(flights, Nil)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 5)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(1.0, 1.0, 0.0, 0.0, 0.0)))

      resultSummary === expected
    }

    "CSV split ratios " >> {
      "Given a flight with 20 passengers and one CSV split of 25% to eea desk" +
        "When request a crunch " +
        "Then I should see a pax load of 5 (20 * 0.25)" >> {
        val scheduled1 = "2017-01-01T00:00Z"

        val flights = List(Flights(List(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1, iata = "BA0001", terminal = "T1", actPax = 20)
        )))

        val procTimes: Map[PaxTypeAndQueue, Double] = Map(
          eeaMachineReadableToDesk -> 20d / 60,
          eeaMachineReadableToEGate -> 35d / 60)

        val testProbe = TestProbe()
        val runnableGraphDispatcher: (List[Flights], List[Set[VoyageManifest]]) => AskableActorRef =
          runCrunchGraph(
            procTimes = procTimes,
            testProbe = testProbe,
            crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled1)).millisSinceEpoch,
            csvSplitsProvider = _ => Option(SplitRatios(
              SplitSources.Historical,
              SplitRatio(eeaMachineReadableToDesk, 0.25)
            ))
          )

        runnableGraphDispatcher(flights, Nil)

        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = paxLoadsFromCrunchState(result, 5)

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(5.0, 0.0, 0.0, 0.0, 0.0)))

        resultSummary === expected
      }
    }

//    "Split source precedence " >> {
//      "Given a flight with both api & csv splits " +
//        "When I crunch " +
//        "I should see pax loads calculated from the api splits, ie 15 pax in first minute not 10 " >> {
//
//        val scheduled1 = "2017-01-01T00:00Z"
//
//        val flights = List(Flights(List(
//          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1, iata = "BA0001", terminal = "T1", actPax = 1, airportId = "LHR")
//        )))
//
//        val procTimes: Map[PaxTypeAndQueue, Double] = Map(
//          eeaMachineReadableToDesk -> 20d / 60,
//          eeaMachineReadableToEGate -> 35d / 60)
//
//        val testProbe = TestProbe()
//        val runnableGraphDispatcher: (List[Flights], List[Set[VoyageManifest]]) => AskableActorRef =
//          runCrunchGraph(
//            procTimes = procTimes,
//            testProbe = testProbe,
//            crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled1)).millisSinceEpoch,
//            csvSplitsProvider = _ => Option(SplitRatios(
//              SplitSources.Historical,
//              SplitRatio(eeaMachineReadableToDesk, 0.5)
//            ))
//          )
//
//        val voyageManifest = Set(
//          VoyageManifest(
//            EventCodes.CheckIn, "LHR", "", "0001", "BA", "2017-01-01", "00:00:00", List(PassengerInfoJson(Option("P"), "GBR", "EEA", Option("22"), Option("LHR"), "N", Option("GBR"), Option("GBR")))))
//
//        runnableGraphDispatcher(flights, List(voyageManifest))
//
//        testProbe.expectMsgAnyClassOf(classOf[CrunchState])
//        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
//        val resultSummary = paxLoadsFromCrunchState(result, 5)
//
//        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(1.0, 0.0, 0.0, 0.0, 0.0)))
//
//        resultSummary === expected
//      }
//    }
  }
}
