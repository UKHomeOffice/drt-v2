package services.crunch

import akka.NotUsed
import akka.stream.scaladsl.Source
import akka.testkit.TestProbe
import controllers.ArrivalGenerator
import drt.shared.Crunch.PortState
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import services.SDate
import services.graphstages.Crunch._

import scala.collection.immutable.List

class CrunchQueueAndTerminalValidationSpec extends CrunchTestLike {
  "Queue validation " >> {
    "Given a flight with transfers " +
      "When I ask for a crunch " +
      "Then I should see only the non-transfer queue" >> {
      val scheduled00 = "2017-01-01T00:00Z"
      val scheduled = "2017-01-01T00:00Z"

      val flights = List(Flights(List(
        ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled00, iata = "BA0001", terminal = "T1", actPax = 15)
      )))

      val fiveMinutes = 600d / 60
      val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

      val testProbe = TestProbe()
      val runnableGraphDispatcher =
        runCrunchGraph[NotUsed, NotUsed](
          procTimes = procTimes,
          testProbe = testProbe,
          crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch,
          portSplits = SplitRatios(
            SplitSources.TerminalAverage,
            SplitRatio(eeaMachineReadableToDesk, 1),
            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.Transfer), 1)
          )) _

      runnableGraphDispatcher(Source(List()), Source(flights), Source(List()))

      val result = testProbe.expectMsgAnyClassOf(classOf[PortState])
      val resultSummary = paxLoadsFromPortState(result, 1).flatMap(_._2.keys)

      val expected = Set(Queues.EeaDesk)

      resultSummary === expected
    }
  }

  "Given two flights, one with an invalid terminal " +
    "When I ask for a crunch " +
    "I should only see crunch results for the flight with a valid terminal" >> {

    val scheduled = "2017-01-01T00:00Z"

    val flights = List(Flights(List(
      ArrivalGenerator.apiFlight(flightId = 1, schDt = scheduled, iata = "BA0001", terminal = "T1", actPax = 15),
      ArrivalGenerator.apiFlight(flightId = 2, schDt = scheduled, iata = "FR8819", terminal = "XXX", actPax = 10)
    )))

    val fiveMinutes = 600d / 60
    val procTimes: Map[PaxTypeAndQueue, Double] = Map(eeaMachineReadableToDesk -> fiveMinutes)

    val testProbe = TestProbe()
    val runnableGraphDispatcher =
      runCrunchGraph[NotUsed, NotUsed](procTimes = procTimes,
        testProbe = testProbe,
        crunchStartDateProvider = () => getLocalLastMidnight(SDate(scheduled)).millisSinceEpoch
      ) _

    runnableGraphDispatcher(Source(List()), Source(flights), Source(List()))

    val result = testProbe.expectMsgAnyClassOf(classOf[PortState])
    val resultSummary = paxLoadsFromPortState(result, 30)

    val expected = Map(
      "T1" -> Map(Queues.EeaDesk -> Seq(
        15.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0,
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)))

    resultSummary === expected
  }
}
