package services

import akka.actor._
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.Crunch._

import scala.collection.immutable.{List, Seq}


class CrunchEgateBanksSpec() extends CrunchTestLike {
  "Egate banks handling " >> {
    "Given flights with 20 very expensive passengers and splits to eea desk & egates " +
      "When I ask for desk recs " +
      "Then I should see lower egates recs by a factor of 5 (rounded up)" >> {
      val scheduled = "2017-01-01T00:00Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 10d),
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EGate, 10d)
          ), SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers))))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(
        eeaMachineReadableToDesk -> fiveMinutes,
        eeaMachineReadableToEGate -> fiveMinutes
      )
      val minMaxDesks = Map("T1" -> Map(
        Queues.EeaDesk -> ((List.fill[Int](24)(0), List.fill[Int](24)(20))),
        Queues.EGate -> ((List.fill[Int](24)(0), List.fill[Int](24)(20)))))
      val slaByQueue = Map(Queues.EeaDesk -> 25, Queues.EGate -> 25)

      val testProbe = TestProbe()
      val subscriber: ActorRef = flightsSubscriber(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduled).millisSinceEpoch
      val endTime = startTime + (29 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = deskRecsFromCrunchState(result, 15)

      val expected = Map("T1" -> Map(
        Queues.EeaDesk -> Seq(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7),
        Queues.EGate -> Seq(2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2)
      ))

      resultSummary === expected
    }
  }

}
