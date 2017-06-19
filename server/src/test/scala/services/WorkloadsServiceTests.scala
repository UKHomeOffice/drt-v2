package services

import drt.services.AirportConfigHelpers
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import drt.shared._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import org.specs2.mutable.SpecificationLike
import services.workloadcalculator.PaxLoadCalculator.{MillisSinceEpoch, PaxTypeAndQueueCount}
import services.workloadcalculator.{PaxLoadCalculator, WorkloadCalculator}

import scala.collection.Set
import scala.collection.immutable.{IndexedSeq, Iterable}
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import controllers.ArrivalGenerator.apiFlight

class WorkloadsServiceTests extends SpecificationLike with AirportConfigHelpers {
  "WorkloadsCalculator" >> {
    "Given a flight with 10 pax with processing time of 20 seconds, " +
      "when we ask for the terminal workloads, " +
      "then we should see 1 minute with 200 workload" >> {
      val wc = new WorkloadCalculator {
        def splitRatioProvider = (apiFlight: Arrival) => {
          Some(SplitRatios(
            TestAirportConfig,

            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 1)))
        }

        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 20d

        def pcpArrivalTimeProvider(flight: Arrival) = MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)

        def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
          PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, BestPax.bestPax)(flight)
      }

      val flightsFuture = Future.successful(List(apiFlight(flightId = 0, iata = "BA0001", actPax = 10, schDt = "2016-01-01T00:00:00", terminal = "A1")))

      val resultFuture = wc.queueLoadsByTerminal(flightsFuture, PaxLoadCalculator.queueWorkAndPaxLoadCalculator)

      val terminalWorkload = extractTerminalWorkload(Await.result(resultFuture, 15 seconds))

      val expected = Set(("A1", List(10 * 20d)))
      println(s"terminalWorkload: $terminalWorkload")

      terminalWorkload == expected
    }

    "Given 2 flights arriving at T1 & T2, " +
      "when we ask for the terminal workloads, " +
      "then we should see each terminal's processing times applied " >> {
      val wc = new WorkloadCalculator {
        def splitRatioProvider = (apiFlight: Arrival) => {
          Some(SplitRatios(
            TestAirportConfig,
            SplitRatio(PaxTypeAndQueue(PaxTypes.EeaMachineReadable, Queues.EeaDesk), 1)))
        }

        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = {
          Map("A1" -> 20d, "A2" -> 40d)(terminalName)
        }

        def pcpArrivalTimeProvider(flight: Arrival) = MilliDate(SDate.parseString(flight.SchDT).millisSinceEpoch)

        def flightPaxTypeAndQueueCountsFlow(flight: Arrival): IndexedSeq[(MillisSinceEpoch, PaxTypeAndQueueCount)] =
          PaxLoadCalculator.flightPaxFlowProvider(splitRatioProvider, BestPax.bestPax)(flight)
      }

      val flightsFuture = Future.successful(List(
        apiFlight(flightId = 0, iata = "BA0001", actPax = 10, schDt = "2016-01-01T00:00:00", terminal = "A1"),
        apiFlight(flightId = 0, iata = "BA0002", actPax = 10, schDt = "2016-01-01T00:00:00", terminal = "A2")
      ))

      val resultFuture = wc.queueLoadsByTerminal(flightsFuture, PaxLoadCalculator.queueWorkAndPaxLoadCalculator)

      val terminalWorkload = extractTerminalWorkload(Await.result(resultFuture, 15 seconds))

      val expected = Set(("A1", List(10 * 20d)), ("A2", List(10 * 40d)))

      terminalWorkload == expected
    }
  }

  def extractTerminalWorkload(result: Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]]): Set[(TerminalName, Iterable[Double])] = {
    result.map(tq => (tq._1, tq._2.flatMap(qwl => qwl._2._1.map(wl => wl.workload)))).toSet
  }
}
