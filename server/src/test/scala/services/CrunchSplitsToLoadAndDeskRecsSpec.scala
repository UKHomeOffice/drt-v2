import akka.actor.ActorRef
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues.{eeaMachineReadableToDesk, eeaMachineReadableToEGate}
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.DateTimeZone
import services.Crunch.CrunchState
import services.{CrunchTestLike, SDate}

import scala.collection.immutable.{List, Seq}


class StreamingSpec extends CrunchTestLike {
  isolated
  sequential

  "Crunch split workload flow " >> {
    "Given a flight with 21 passengers and splits to eea desk & egates " +
      "When I ask for queue loads " +
      "Then I should see 4 queue loads, 2 for the first 20 pax to each queue and 2 for the last 1 split to each queue" >> {
      val scheduled = "2017-01-01T00:00Z"
      val totalPax = 21
      val edSplit = 0.25
      val egSplit = 0.75
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, edSplit * totalPax),
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, egSplit * totalPax)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val procTimes: Map[PaxTypeAndQueue, Double] = Map(
        eeaMachineReadableToDesk -> 20d / 60,
        eeaMachineReadableToEGate -> 35d / 60)

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result = result, minutesToTake = 2)

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
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
          List(ApiSplits(
            List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1d)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))),
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled2),
          List(ApiSplits(
            List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1d)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result = result, minutesToTake = 5)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(1.0, 1.0, 0.0, 0.0, 0.0)))

      resultSummary === expected
    }

    "Given 2 flights with one passenger each and one split to eea desk arriving at pcp 1 minute apart" +
      "When crunch queue workloads between two times " +
      "Then I should emit a CrunchStateDiff with the expected pax loads" >> {
      val scheduled1 = "2017-01-01T00:00Z"
      val scheduled2 = "2017-01-01T00:01Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1),
          List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1d)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))),
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled2),
          List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1d)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result = result, minutesToTake = 5)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(1.0, 1.0, 0.0, 0.0, 0.0)))

      resultSummary === expected
    }

    "CSV split ratios " >> {
      "Given a flight with 20 passengers and one CSV split of 25% to eea desk" +
        "When request a crunch " +
        "Then I should see a pax load of 5 (20 * 0.25)" >> {
        val scheduled1 = "2017-01-01T00:00Z"
        val flightsWithSplits = List(
          ApiFlightWithSplits(
            ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1, actPax = 20),
            List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 25)), SplitSources.Historical, Percentage))))

        val testProbe = TestProbe()
        val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

        val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
        val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

        initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = paxLoadsFromCrunchState(result, 5)

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(5.0, 0.0, 0.0, 0.0, 0.0)))

        resultSummary === expected
      }
    }

    "CSV split ratios " >> {
      "Given a flight with 20 passengers and one CSV split of 25% to eea desk" +
        "When request a crunch " +
        "Then I should see a pax load of 5 (20 * 0.25)" >> {
        val scheduled1 = "2017-01-01T00:00Z"
        val flightsWithSplits = List(
          ApiFlightWithSplits(
            ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1, actPax = 20),
            List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 25)), SplitSources.Historical, Percentage))))

        val testProbe = TestProbe()
        val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

        val startTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch
        val endTime = SDate(scheduled1, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

        initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = paxLoadsFromCrunchState(result, 5)

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(5.0, 0.0, 0.0, 0.0, 0.0)))

        resultSummary === expected
      }
    }

    "Split source precedence " >> {
      "Given a flight with both api & csv splits " +
        "When I crunch " +
        "I should see pax loads calculated from the api splits, ie 15 pax in first minute not 10 " >> {
        val scheduled = "2017-01-01T00:00Z"
        val flightsWithSplits = List(
          ApiFlightWithSplits(
            ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, actPax = 20),
            List(
              ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage),
              ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 15)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers)
            )))

        val testProbe = TestProbe()
        val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

        val startTime = SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch
        val endTime = SDate(scheduled, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

        initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = paxLoadsFromCrunchState(result, 5)

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(15.0, 0.0, 0.0, 0.0, 0.0)))

        resultSummary === expected
      }
    }
  }
}
