package services.simulations

import drt.shared._
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.{FeedSource, LiveFeedSource, PaxTypesAndQueues, Queues}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.util.{Failure, Success}

class SimulationQueryStringSpec extends Specification {
  "When parsing a query string back into a simulations params object" >> {
    "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
      val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&eGateOpenHours=1,2&desks=10"
      val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
      val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

      val expected = Success(SimulationParams(
        terminal = Terminal("T1"),
        date = LocalDate(2020, 2, 2),
        passengerWeighting = 1.0,
        processingTimes = Map(),
        minDesksByQueue = Map(),
        maxDesks = 10,
        eGateBankSizes = IndexedSeq.fill(5)(5),
        slaByQueue = Map(),
        crunchOffsetMinutes = 0,
        eGateOpenHours = Seq(1, 2),
      ))

      val result = SimulationParams.fromQueryStringParams(qsValues)

      result === expected
    }
  }

  "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=1,2&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30&" +
      "EGate_min=1&NonEeaDesk_min=1&" +
      "desks=6&" +
      "EGate_sla=10&EeaDesk_sla=15"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      terminal = Terminal("T1"),
      date = LocalDate(2020, 2, 2),
      passengerWeighting = 1.0,
      processingTimes = Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60, PaxTypesAndQueues.eeaMachineReadableToEGate -> 30),
      minDesksByQueue = Map(Queues.EGate -> 1, Queues.NonEeaDesk -> 1),
      maxDesks = 6,
      eGateBankSizes = IndexedSeq.fill(5)(5),
      slaByQueue = Map(Queues.EGate -> 10, Queues.EeaDesk -> 15),
      crunchOffsetMinutes = 0,
      eGateOpenHours = Seq(1, 2),
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    result === expected
  }

  "Given a query string map containing all invalid types then they should be ignored if they are not required" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=1,2&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "desks=3&" +
      "EGate_sla=10&EeaDesk_sla=x"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      terminal = Terminal("T1"),
      date = LocalDate(2020, 2, 2),
      passengerWeighting = 1.0,
      processingTimes = Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60),
      minDesksByQueue = Map(Queues.EGate -> 1),
      maxDesks = 3,
      eGateBankSizes = IndexedSeq.fill(5)(5),
      slaByQueue = Map(Queues.EGate -> 10),
      crunchOffsetMinutes = 0,
      eGateOpenHours = Seq(1, 2),
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    result === expected
  }

  "Given a query string containing no open egate hours I should get back a valid SimulationParams with an empty Seq for eGate open hours" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "desks=3&" +
      "EGate_sla=10&EeaDesk_sla=x"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      terminal = Terminal("T1"),
      date = LocalDate(2020, 2, 2),
      passengerWeighting = 1.0,
      processingTimes = Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60),
      minDesksByQueue = Map(Queues.EGate -> 1),
      maxDesks = 3,
      eGateBankSizes = IndexedSeq.fill(5)(5),
      slaByQueue = Map(Queues.EGate -> 10),
      crunchOffsetMinutes = 0,
      eGateOpenHours = Seq(),
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    result === expected
  }

  "Missing required fields then I should get back a failure" >> {
    val qs = "date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "desks=x&" +
      "EGate_sla=10&EeaDesk_sla=x"
    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val result = SimulationParams.fromQueryStringParams(qsValues)

    result.isInstanceOf[Failure[SimulationParams]]
  }
}
