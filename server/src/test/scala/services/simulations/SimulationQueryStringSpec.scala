package services.simulations

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import org.specs2.mutable.Specification
import play.api.test.FakeRequest
import uk.gov.homeoffice.drt.ports.{PaxTypesAndQueues, Queues}

import scala.util.{Failure, Success}

class SimulationQueryStringSpec extends Specification {
  "When parsing a query string back into a simulations params object" >> {
    "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
      val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&eGateOpenHours=1,2"
      val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
      val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

      val expected = Success(SimulationParams(
        Terminal("T1"),
        LocalDate(2020, 2, 2),
        1.0,
        Map(),
        Map(),
        Map(),
        IndexedSeq.fill(5)(5),
        Map(),
        0,
        Seq(1, 2)
      ))

      val result = SimulationParams.fromQueryStringParams(qsValues)

      expected === result
    }
  }

  "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=1,2&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30&" +
      "EGate_min=1&NonEeaDesk_min=1&" +
      "EGate_max=3&NonEeaDesk_max=3&" +
      "EGate_sla=10&EeaDesk_sla=15"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      Terminal("T1"),
      LocalDate(2020, 2, 2),
      1.0,
      Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60, PaxTypesAndQueues.eeaMachineReadableToEGate -> 30),
      Map(Queues.EGate -> 1, Queues.NonEeaDesk -> 1),
      Map(Queues.EGate -> 3, Queues.NonEeaDesk -> 3),
      IndexedSeq.fill(5)(5),
      Map(Queues.EGate -> 10, Queues.EeaDesk -> 15),
      0,
      Seq(1, 2)
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    expected === result
  }

  "Given a query string map containing all invalid types then they should be ignored if they are not required" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=1,2&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "EGate_max=3&NonEeaDesk_max=x&" +
      "EGate_sla=10&EeaDesk_sla=x"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      Terminal("T1"),
      LocalDate(2020, 2, 2),
      1.0,
      Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60),
      Map(Queues.EGate -> 1),
      Map(Queues.EGate -> 3),
      IndexedSeq.fill(5)(5),
      Map(Queues.EGate -> 10),
      0,
      Seq(1,2)
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    expected === result
  }

  "Given a query string containing no open egate hours I should get back a valid SimulationParams with an empty Seq for eGate open hours" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "eGateOpenHours=&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "EGate_max=3&NonEeaDesk_max=x&" +
      "EGate_sla=10&EeaDesk_sla=x"

    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val expected = Success(SimulationParams(
      Terminal("T1"),
      LocalDate(2020, 2, 2),
      1.0,
      Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60),
      Map(Queues.EGate -> 1),
      Map(Queues.EGate -> 3),
      IndexedSeq.fill(5)(5),
      Map(Queues.EGate -> 10),
      0,
      Seq()
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    expected === result
  }

  "Missing required fields then I should get back a failure" >> {
    val qs = "date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
      "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=x&" +
      "EGate_min=1&NonEeaDesk_min=x&" +
      "EGate_max=3&NonEeaDesk_max=x&" +
      "EGate_sla=10&EeaDesk_sla=x"
    val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
    val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

    val result = SimulationParams.fromQueryStringParams(qsValues)

    result.isInstanceOf[Failure[SimulationParams]]
  }
}
