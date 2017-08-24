package services

import actors.{GetFlights, GetPortWorkload}
import akka.pattern.AskableActorRef
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.{ArrivalGenerator, GetTerminalCrunch}
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.DateTimeZone
import services.Crunch.CrunchState

import scala.collection.immutable.List
import scala.concurrent.Await
import scala.concurrent.duration._

class CrunchWindowRelevantDataSpec extends CrunchTestLike {
  isolated
  sequential

  "Relevant crunch state data for crunch window " >> {
    "Given two flights one reaching PCP before the crunch start time and one after " +
      "When I crunch and ask for flights " +
      "I should see only see the flight reaching PCP after the crunch start time " >> {
      val scheduledBeforeCrunchStart = "2017-01-01T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flightOutsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledBeforeCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))
      val flightInsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))

      val flightsWithSplits = List(flightOutsideCrunchWindow, flightInsideCrunchWindow)

      val testProbe = TestProbe()
      val (subscriber, crunchStateTestActor) = flightsSubscriberAndCrunchStateTestActor(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch + (29 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val askableCrunchStateTestActor: AskableActorRef = crunchStateTestActor
      val result = Await.result(askableCrunchStateTestActor.ask(GetFlights)(new Timeout(1 second)), 1 second).asInstanceOf[FlightsWithSplits]

      val expected = FlightsWithSplits(List(flightInsideCrunchWindow))

      result === expected
    }

    "Given two flights one reaching PCP before the crunch start time and one after " +
      "When I crunch and ask for workloads " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledBeforeCrunchStart = "2017-01-01T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flightOutsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledBeforeCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))
      val flightInsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))

      val flightsWithSplits = List(flightOutsideCrunchWindow, flightInsideCrunchWindow)

      val testProbe = TestProbe()
      val (subscriber, crunchStateTestActor) = flightsSubscriberAndCrunchStateTestActor(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch + (1439 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val askableCrunchStateTestActor: AskableActorRef = crunchStateTestActor
      val result = Await
        .result(askableCrunchStateTestActor.ask(GetPortWorkload)(new Timeout(1 second)), 1 second)
        .asInstanceOf[Map[TerminalName, Map[QueueName, (List[WL], List[Pax])]]]

      val wl = result("T1")(Queues.EeaDesk)._1

      val expectedLength = 1440
      val expectedWl = startTime to endTime by oneMinute

      (wl.length, wl.map(_.time).toSet) === (expectedLength, expectedWl.toSet)
    }

    "Given two flights one reaching PCP after the crunch window and one during " +
      "When I crunch and ask for workloads " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledAfterCrunchEnd = "2017-01-03T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flightOutsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledAfterCrunchEnd, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))
      val flightInsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))

      val flightsWithSplits = List(flightOutsideCrunchWindow, flightInsideCrunchWindow)

      val testProbe = TestProbe()
      val (subscriber, crunchStateTestActor) = flightsSubscriberAndCrunchStateTestActor(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch + (1439 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val askableCrunchStateTestActor: AskableActorRef = crunchStateTestActor
      val result = Await
        .result(askableCrunchStateTestActor.ask(GetPortWorkload)(new Timeout(1 second)), 1 second)
        .asInstanceOf[Map[TerminalName, Map[QueueName, (List[WL], List[Pax])]]]

      val wl = result("T1")(Queues.EeaDesk)._1

      val expectedLength = 1440
      val expectedWl = startTime to endTime by oneMinute

      (wl.length, wl.map(_.time).toSet) === (expectedLength, expectedWl.toSet)
    }

    "Given two flights one reaching PCP before the crunch start time and one after " +
      "When I crunch and ask for crunch results " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledBeforeCrunchStart = "2017-01-01T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flightOutsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledBeforeCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))
      val flightInsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))

      val flightsWithSplits = List(flightOutsideCrunchWindow, flightInsideCrunchWindow)

      val testProbe = TestProbe()
      val (subscriber, crunchStateTestActor) = flightsSubscriberAndCrunchStateTestActor(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch + (1439 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val askableCrunchStateTestActor: AskableActorRef = crunchStateTestActor
      val result = Await
        .result(askableCrunchStateTestActor.ask(GetTerminalCrunch("T1"))(new Timeout(1 second)), 1 second)
        .asInstanceOf[List[(QueueName, Right[NoCrunchAvailable, CrunchResult])]]

      val deskRecMinutes = result.head._2.b.recommendedDesks.length

      val expected = 1440

      deskRecMinutes === expected
    }

    "Given two flights one reaching PCP after the crunch window and one during " +
      "When I crunch and ask for crunch results " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledAfterCrunchEnd = "2017-01-03T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flightOutsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledAfterCrunchEnd, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))
      val flightInsideCrunchWindow = ApiFlightWithSplits(
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, actPax = 20),
        List(ApiSplits(List(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 50)), SplitSources.Historical, Percentage)))

      val flightsWithSplits = List(flightOutsideCrunchWindow, flightInsideCrunchWindow)

      val testProbe = TestProbe()
      val (subscriber, crunchStateTestActor) = flightsSubscriberAndCrunchStateTestActor(procTimes, slaByQueue, minMaxDesks, queues, testProbe, validTerminals)

      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch + (1439 * oneMinute)

      initialiseAndSendFlights(flightsWithSplits, subscriber, startTime, endTime)

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val askableCrunchStateTestActor: AskableActorRef = crunchStateTestActor
      val result = Await
        .result(askableCrunchStateTestActor.ask(GetTerminalCrunch("T1"))(new Timeout(1 second)), 1 second)
        .asInstanceOf[List[(QueueName, Right[NoCrunchAvailable, CrunchResult])]]

      val deskRecMinutes = result.head._2.b.recommendedDesks.length

      val expected = 1440

      deskRecMinutes === expected
    }
  }
}
