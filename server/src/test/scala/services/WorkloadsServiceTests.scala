package services

import spatutorial.shared.SplitRatiosNs.{SplitRatio, SplitRatios}
import spatutorial.shared._
import spatutorial.shared.FlightsApi.{QueueName, TerminalName}
import org.specs2.mutable.SpecificationLike

import scala.collection.Set
import scala.collection.immutable.Iterable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class WorkloadsServiceTests extends SpecificationLike {
  def apiFlight(iataFlightCode: String,
                totalPax: Int, scheduledDatetime: String,
                terminal: String
               ): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 1,
      ActPax = totalPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 1,
      AirportID = "EDI",
      Terminal = terminal,
      ICAO = "",
      IATA = iataFlightCode,
      Origin = "",
      PcpTime = 0,
      SchDT = scheduledDatetime
    )

  "WorkloadsCalculator" >> {
    "Given a flight with 10 pax with processing time of 20 seconds, " +
      "when we ask for the terminal workloads, " +
      "then we should see 1 minute with 200 workload" >> {
      val wc = new WorkloadsCalculator {
        override def splitRatioProvider = (apiFlight: ApiFlight) => {
          Some(SplitRatios(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 1)))
        }

        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = 20d
      }

      val flightsFuture = Future.successful(List(apiFlight(iataFlightCode = "BA0001", totalPax = 10, scheduledDatetime = "2016-01-01T00:00:00", terminal = "A1")))

      val resultFuture = wc.workAndPaxLoadsByTerminal(flightsFuture)

      val terminalWorkload = extractTerminalWorkload(Await.result(resultFuture, 15 seconds))

      val expected = Set(("A1", List(10 * 20d)))
      println(s"terminalWorkload: $terminalWorkload")

      terminalWorkload == expected
    }

    "Given 2 flights arriving at T1 & T2, " +
      "when we ask for the terminal workloads, " +
      "then we should see each terminal's processing times applied " >> {
      val wc = new WorkloadsCalculator {
        override def splitRatioProvider = (apiFlight: ApiFlight) => {
          Some(SplitRatios(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 1)))
        }

        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = {
          Map("A1" -> 20d, "A2" -> 40d)(terminalName)
        }
      }

      val flightsFuture = Future.successful(List(
        apiFlight(iataFlightCode = "BA0001", totalPax = 10, scheduledDatetime = "2016-01-01T00:00:00", terminal = "A1"),
        apiFlight(iataFlightCode = "BA0002", totalPax = 10, scheduledDatetime = "2016-01-01T00:00:00", terminal = "A2")
      ))

      val resultFuture = wc.workAndPaxLoadsByTerminal(flightsFuture)

      val terminalWorkload = extractTerminalWorkload(Await.result(resultFuture, 15 seconds))

      val expected = Set(("A1", List(10 * 20d)), ("A2", List(10 * 40d)))

      terminalWorkload == expected
    }
  }

  def extractTerminalWorkload(result: Map[TerminalName, Map[QueueName, (Seq[WL], Seq[Pax])]]): Set[(TerminalName, Iterable[Double])] = {
    result.map(tq => (tq._1, tq._2.flatMap(qwl => qwl._2._1.map(wl => wl.workload)))).toSet
  }
}
