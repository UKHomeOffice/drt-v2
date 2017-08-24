package services

import akka.actor._
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.Crunch._

import scala.collection.immutable.{List, Seq}


class CrunchCodeSharesSpec() extends CrunchTestLike {
  "Code shares " >> {
    "Given 2 flights which are codeshares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload representing only the flight with the highest passenger numbers" >> {
      val scheduled = "2017-01-01T00:00Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001"),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 10d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))),
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled, iata = "FR8819"),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 10d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)
      val minMaxDesks = Map("T1" -> Map(Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20)))))

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled).millisSinceEpoch
      val endTime = startTime + (29 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchStateDiff])

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 15)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))

      resultSummary === expected
    }

    "Given flights some of which are code shares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload correctly split to the appropriate terminals, and having accounted for code shares" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled15 = "2017-01-01T00:15Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 15d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))),
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled00, iata = "FR8819", terminal = "T1", actPax = 10),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 10d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))),
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled15, iata = "EZ1010", terminal = "T2", actPax = 12),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 12d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val processingTime = 10d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> processingTime)

      val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25)
      val minMaxDesks = Map("T1" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20)))))
      val queues: Map[TerminalName, Seq[QueueName]] = Map("T1" -> Seq(Queues.EeaDesk), "T2" -> Seq(Queues.EeaDesk))

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled00).millisSinceEpoch
      val endTime = startTime + (119 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchStateDiff])

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 30)


      val expected = Map(
        "T1" -> Map(Queues.EeaDesk -> Seq(
          15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        "T2" -> Map(Queues.EeaDesk -> Seq(
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          12.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

      resultSummary === expected
    }

    "Given flights some of which are code shares with each other " +
      "When I ask for a crunch " +
      "Then I should see workload correctly split to the appropriate terminals, and having accounted for code shares" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled15 = "2017-01-01T00:15Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 15d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))),
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled15, iata = "EZ1010", terminal = "xxx", actPax = 12),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 12d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val processingTime = 10d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> processingTime)

      val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25)
      val minMaxDesks = Map("T1" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20)))))

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled00).millisSinceEpoch
      val endTime = startTime + (119 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchStateDiff])

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 30)

      val expected = Map(
        "T1" -> Map(Queues.EeaDesk -> Seq(
          15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
          0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

      resultSummary === expected
    }
  }

}
