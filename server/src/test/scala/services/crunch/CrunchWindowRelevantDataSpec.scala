package services.crunch

import actors.{GetFlights, GetPortWorkload}
import akka.NotUsed
import akka.pattern.AskableActorRef
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import akka.util.Timeout
import controllers.{ArrivalGenerator, GetTerminalCrunch}
import drt.shared.FlightsApi.{Flights, FlightsWithSplits, QueueName, TerminalName}
import drt.shared.PaxTypes.EeaMachineReadable
import drt.shared.PaxTypesAndQueues.eeaMachineReadableToDesk
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared._
import org.joda.time.DateTimeZone
import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}
import services.Crunch.CrunchState
import services.SDate

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

      val flightOutsideCrunch = ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledBeforeCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20)
      val flightInsideCrunch = ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20)

      val flights = List(Flights(List(
        flightOutsideCrunch,
        flightInsideCrunch
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => SDate(scheduledAtCrunchStart).millisSinceEpoch,
          minMaxDesks = minMaxDesks,
          minutesToCrunch = 120
        ) _

      val (_, _, askableCrunchStateTestActor) = runnableGraphDispatcher(Source(flights), Source(List()))

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])


      val result = Await.result(askableCrunchStateTestActor.ask(GetFlights)(new Timeout(1 second)), 1 second).asInstanceOf[FlightsWithSplits]

      val expected = ApiFlightWithSplits(flightInsideCrunch, Set(ApiSplits(Set(ApiPaxTypeAndQueueCount(EeaMachineReadable, Queues.EeaDesk, 100.0)), SplitSources.TerminalAverage, None, Percentage)))

      result.flights === List(expected)
    }

    "Given two flights one reaching PCP before the crunch start time and one after " +
      "When I crunch and ask for workloads " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledBeforeCrunchStart = "2017-01-01T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledBeforeCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20),
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20)
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val minutesToCrunch = 120
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => SDate(scheduledAtCrunchStart).millisSinceEpoch,
          minMaxDesks = minMaxDesks,
          minutesToCrunch = minutesToCrunch
        ) _
      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = startTime + (minutesToCrunch * oneMinute)

      val (_, _, askableCrunchStateTestActor) = runnableGraphDispatcher(Source(flights), Source(List()))

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val result = Await.result(askableCrunchStateTestActor.ask(GetPortWorkload)(new Timeout(1 second)), 1 second)
        .asInstanceOf[Map[TerminalName, Map[QueueName, (List[WL], List[Pax])]]]

      val wl = result("T1")(Queues.EeaDesk)._1

      val expectedLength = minutesToCrunch
      val expectedWl = startTime until endTime by oneMinute

      (wl.length, wl.map(_.time).toSet) === Tuple2(expectedLength, expectedWl.toSet)
    }

    "Given two flights one reaching PCP after the crunch window and one during " +
      "When I crunch and ask for workloads " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledAfterCrunchEnd = "2017-01-03T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledAfterCrunchEnd, iata = "BA0001", terminal = "T1", actPax = 20),
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20)
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val minutesToCrunch = 120
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => SDate(scheduledAtCrunchStart).millisSinceEpoch,
          minMaxDesks = minMaxDesks,
          minutesToCrunch = minutesToCrunch
        ) _
      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val endTime = startTime + (minutesToCrunch * oneMinute)

      val (_, _, askableCrunchStateTestActor) = runnableGraphDispatcher(Source(flights), Source(List()))

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val result = Await.result(askableCrunchStateTestActor.ask(GetPortWorkload)(new Timeout(1 second)), 1 second)
        .asInstanceOf[Map[TerminalName, Map[QueueName, (List[WL], List[Pax])]]]

      val wl = result("T1")(Queues.EeaDesk)._1

      val expectedLength = minutesToCrunch
      val expectedWl = startTime until endTime by oneMinute

      (wl.length, wl.map(_.time).toSet) === Tuple2(expectedLength, expectedWl.toSet)
    }

    "Given two flights one reaching PCP before the crunch start time and one after " +
      "When I crunch and ask for crunch results " +
      "I should see only see minutes falling within the crunch window " >> {
      val scheduledBeforeCrunchStart = "2017-01-01T00:00Z"
      val scheduledAtCrunchStart = "2017-01-02T00:00Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduledBeforeCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20),
        ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduledAtCrunchStart, iata = "BA0001", terminal = "T1", actPax = 20)
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val minutesToCrunch = 120
      val startTime = SDate(scheduledAtCrunchStart, DateTimeZone.UTC).millisSinceEpoch
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed](
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => startTime,
          minMaxDesks = minMaxDesks,
          minutesToCrunch = minutesToCrunch
        ) _

      val (_, _, askableCrunchStateTestActor) = runnableGraphDispatcher(Source(flights), Source(List()))

      testProbe.expectMsgAnyClassOf(classOf[CrunchState])

      val result = Await
        .result(askableCrunchStateTestActor.ask(GetTerminalCrunch("T1"))(new Timeout(1 second)), 1 second)
        .asInstanceOf[TerminalCrunchResult]

      val deskRecMinutes = result.queuesAndCrunchResults.head._2 match {
        case Right(cr) => cr.recommendedDesks.length
      }

      val expected = minutesToCrunch

      deskRecMinutes === expected
    }
  }
}
