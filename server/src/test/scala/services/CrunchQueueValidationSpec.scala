package services

import akka.actor._
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import services.Crunch._

import scala.collection.immutable.List


class CrunchQueueValidationSpec() extends CrunchTestLike {
  "Queue validation " >> {
    "Given a flight with transfers " +
      "When I ask for a crunch " +
      "Then I should see only the non-transfer queue" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val flightsWithSplits = List(
        ApiFlightWithSplits(
          ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15),
          List(ApiSplits(List(
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 15d),
            ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.Transfer, 5d)
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

      val result = testProbe.expectMsgAnyClassOf(classOf[CrunchState])
      val resultSummary = paxLoadsFromCrunchState(result, 1).flatMap(_._2.map(_._1))

      val expected = Set(Queues.EeaDesk)

      resultSummary === expected
    }
  }
}
