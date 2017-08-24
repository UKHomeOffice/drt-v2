package services

import akka.actor._
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.DateTimeZone
import services.Crunch._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable.{List, Seq}


class CrunchTimezoneSpec() extends CrunchTestLike {
  "Crunch timezone " >> {
    "Given an SDateLike for a date outside BST" +
      "When I ask for a corresponding cunch start time " +
      "Then I should get an SDateLike representing the previous midnight UTC" >> {
      val now = SDate("2010-01-02T11:39", DateTimeZone.forID("Europe/London"))

      val result = getLocalLastMidnight(now).millisSinceEpoch
      val expected = SDate("2010-01-02T00:00").millisSinceEpoch

      result === expected
    }

    "Given an SDateLike for a date inside BST" +
      "When I ask for a corresponding cunch start time " +
      "Then I should get an SDateLike representing the previous midnight UTC" >> {
      val now = SDate("2010-07-02T11:39", DateTimeZone.forID("Europe/London"))
      val result: MillisSinceEpoch = getLocalLastMidnight(now).millisSinceEpoch
      val expected = SDate("2010-07-01T23:00").millisSinceEpoch

      result === expected
    }

    "Given flights with one passenger and one split to eea desk" +
      "When the date falls within GMT" +
      "Then I should see desks being allocated at the time passengers start arriving at PCP" >> {
      val scheduled = "2017-01-01T00:00Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
          List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1d)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate("2017-05-30T23:00Z").millisSinceEpoch
      val endTime = startTime + (119 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = deskRecsFromCrunchState(result, 30)

      val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1
      )))

      resultSummary === expected
    }

    "Min / Max desks in BST " >> {
      "Given flights with one passenger and one split to eea desk " +
        "When the date falls within BST " +
        "Then I should see min desks allocated in alignment with BST" >> {
        val scheduled1amBST = "2017-06-01T00:00Z"
        val flightsWithSplits = List(
          ApiFlightWithSplits(
            ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled1amBST),
            List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 1d)), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

        val minMaxDesks = Map("T1" -> Map(Queues.EeaDesk -> Tuple2(0 :: 5 :: List.fill[Int](22)(0), List.fill[Int](24)(20))))

        val testProbe = TestProbe()
        val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

        val startTime = SDate("2017-05-30T23:00Z").millisSinceEpoch
        val endTime = startTime + (119 * oneMinute)

        initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

        val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
        val resultSummary = deskRecsFromCrunchState(result, 120)

        val expected = Map("T1" -> Map(Queues.EeaDesk -> Seq(
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
          5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
          5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5
        )))

        resultSummary === expected
      }

      "Given a list of Min or Max desks" >> {
        "When parsing a BST date then we should get BST min/max desks" >> {
          val testMaxDesks = List(0, 1, 2, 3, 4, 5)
          val startTimeMidnightBST = SDate("2017-06-01T00:00Z").addHours(-1).millisSinceEpoch

          val oneHour = oneMinute * 60
          val startTimes = startTimeMidnightBST to startTimeMidnightBST + (oneHour * 5) by oneHour

          val expected = List(0, 1, 2, 3, 4, 5)
          startTimes.map(desksForHourOfDayInUKLocalTime(_, testMaxDesks)) === expected
        }
        "When parsing a GMT date then we should get BST min/max desks" >> {
          val testMaxDesks = List(0, 1, 2, 3, 4, 5)
          val startTimeMidnightGMT = SDate("2017-01-01T00:00Z").millisSinceEpoch

          val oneHour = oneMinute * 60
          val startTimes = startTimeMidnightGMT to startTimeMidnightGMT + (oneHour * 5) by oneHour

          val expected = List(0, 1, 2, 3, 4, 5)
          startTimes.map(desksForHourOfDayInUKLocalTime(_, testMaxDesks)) === expected
        }
      }
    }
  }
}
