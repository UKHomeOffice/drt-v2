package services.crunch

import akka.NotUsed
import akka.actor.ActorRef
import akka.stream.OverflowStrategy
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe

import scala.concurrent.duration._
import controllers.ArrivalGenerator
import drt.shared.Crunch.CrunchState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import passengersplits.parsing.VoyageManifestParser.{PassengerInfoJson, VoyageManifest, VoyageManifests}
import services.graphstages.Crunch.getLocalLastMidnight
import services.SDate

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
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed, NotUsed](
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch,
          portSplits = SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, edSplit),
            SplitRatio(eeaMachineReadableToEGate, egSplit)
          )) _

      runnableGraphDispatcher(Source(List()), Source(flights), Source(List()))

      val result = testProbe.expectMsgAnyClassOf(5 seconds, classOf[CrunchState])
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

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed, NotUsed](
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled1)).millisSinceEpoch
        ) _

      runnableGraphDispatcher(Source(List()), Source(flights), Source(List()))

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 5)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(1.0, 1.0, 0.0, 0.0, 0.0)))

      resultSummary === expected
    }

    "Given 1 flight with 100 passengers eaa splits to desk and eGates" +
      "When I ask for queue loads " +
      "Then I should see the correct loads for each queue" >> {
      val scheduled = "2017-01-01T00:00Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 100)
      )))
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(
        eeaMachineReadableToDesk -> 0.25,
        eeaMachineReadableToEGate -> 0.3,
        eeaNonMachineReadableToDesk -> 0.4
      )

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed, NotUsed](
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch,
          procTimes = procTimes,
          portSplits = SplitRatios(
            SplitSources.TerminalAverage,
            List(SplitRatio(eeaMachineReadableToDesk, 0.25),
              SplitRatio(eeaMachineReadableToEGate, 0.25),
              SplitRatio(eeaNonMachineReadableToDesk, 0.5)
            )
          )) _

      runnableGraphDispatcher(Source(List()), Source(flights), Source(List()))

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = workLoadsFromCrunchState(result, 5)


      val expected = Map("T1" -> Map(
        "eeaDesk" -> List(5.25, 5.25, 5.25, 5.25, 5.25),
        "eGate" -> List(1.5, 1.5, 1.5, 1.5, 1.5))
      )

      resultSummary === expected
    }

    "CSV split ratios " >> {
      "Given a flight with 20 passengers and one CSV split of 25% to eea desk " +
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
        val runnableGraphDispatcher =
          runCrunchGraph[NotUsed, NotUsed](
            procTimes = procTimes,
            testProbe = testProbe,
            crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled1)).millisSinceEpoch,
            csvSplitsProvider = _ => Option(SplitRatios(
              SplitSources.Historical,
              SplitRatio(eeaMachineReadableToDesk, 0.25)
            ))) _

        runnableGraphDispatcher(Source(List()), Source(flights), Source(List()))

        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = paxLoadsFromCrunchState(result, 5)

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(5.0, 0.0, 0.0, 0.0, 0.0)))

        resultSummary === expected
      }
    }

    "Split source precedence " >> {
      "Given a flight with both api & csv splits " +
        "When I crunch " +
        "I should see pax loads calculated from the api splits, ie 1 pax in first minute not 10 " >> {

        val scheduled1 = "2017-01-01T00:00Z"

        val flights = Flights(List(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1, iata = "BA0001", terminal = "T1", actPax = 10, airportId = "LHR")
        ))

        val procTimes: Map[PaxTypeAndQueue, Double] = Map(
          eeaMachineReadableToDesk -> 20d / 60,
          eeaMachineReadableToEGate -> 35d / 60)

        val testProbe = TestProbe()
        val runnableGraphDispatcher =
          runCrunchGraph[ActorRef, ActorRef](
            procTimes = procTimes,
            testProbe = testProbe,
            crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled1)).millisSinceEpoch,
            csvSplitsProvider = _ => Option(SplitRatios(
              SplitSources.Historical,
              SplitRatio(eeaMachineReadableToDesk, 0.5)
            ))
          ) _

        val voyageManifests = VoyageManifests(Set(
          VoyageManifest(DqEventCodes.CheckIn, "LHR", "JFK", "0001", "BA", "2017-01-01", "00:00", List(
            PassengerInfoJson(Some("P"), "GBR", "EEA", Some("22"), Some("LHR"), "N", Some("GBR"), Option("GBR"))
          ))
        ))


        val baseFlights = Source.actorRef(1, OverflowStrategy.dropBuffer)
        val flightsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)
        val manifestsSource = Source.actorRef(1, OverflowStrategy.dropBuffer)

        val (_, fs, ms, _, _) = runnableGraphDispatcher(baseFlights, flightsSource, manifestsSource)

        fs ! flights
        Thread.sleep(250L)
        ms ! voyageManifests

        testProbe.expectMsgAnyClassOf(5 seconds, classOf[CrunchState])
        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = paxLoadsFromCrunchState(result, 5)

        val expected = Map("T1" -> Map(
          Queues.EeaDesk -> Seq(0.0, 0.0, 0.0, 0.0, 0.0),
          Queues.EGate -> Seq(1.0, 0.0, 0.0, 0.0, 0.0)
        ))

        resultSummary === expected
      }
    }
  }
}
