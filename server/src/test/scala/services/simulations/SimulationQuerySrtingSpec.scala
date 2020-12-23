package services.simulations

import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import org.specs2.mutable.Specification
import play.api.test.FakeRequest

import scala.util.{Failure, Success}

class SimulationQuerySrtingSpec extends Specification {

  "Given I am converting simulation params into a query string with a minimal data set" >> {

    val simulationParams = SimulationParams(
      Terminal("T1"),
      LocalDate(2020, 2, 2),
      1.0,
      Map(),
      Map(),
      Map(),
      5,
      Map(),
      0
    )

    val result = simulationParams.toQueryStringParams

    "Then I should see the correct terminal in the query string" >> {
      val expected = "terminal=T1"

      result must contain(expected)
    }

    "Then I should see the correct date in the query string" >> {
      val expected = "date=2020-02-02"

      result must contain(expected)
    }

    "Then I should see the correct passenger weighting in the query string" >> {
      val expected = "passengerWeighting=1.0"

      result must contain(expected)
    }

    "Then I should see the correct passenger weighting in the query string" >> {
      val expected = "eGateBankSize=5"

      result must contain(expected)
    }

    "Then I should see the correct crunch offset in the query string" >> {
      val expected = "crunchOffsetMinutes=0"

      result must contain(expected)
    }

    "Then I should not see an entry for minDesks" >> {
      val expectedNot = "minDesks"

      result must not contain (expectedNot)
    }

    "Then I should not see an entry for maxDesks" >> {
      val expectedNot = "maxDesks"

      result must not contain (expectedNot)
    }

    "Then I should not see an entry for processingTimes" >> {
      val expectedNot = "processingTimes"

      result must not contain (expectedNot)
    }

    "Then I should not see an entry for slaByQueue" >> {
      val expectedNot = "slaByQueue"

      result must not contain (expectedNot)
    }

    "Then I should get a valid query string back" >> {

      val expected = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSize=5&crunchOffsetMinutes=0"

      result === expected
    }

  }

  "Given I am converting simulation params into a query string with queue data" >> {

    val simulationParams = SimulationParams(
      Terminal("T1"),
      LocalDate(2020, 2, 2),
      1.0,
      Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> 60, PaxTypesAndQueues.eeaMachineReadableToEGate -> 30),
      Map(Queues.EGate -> 1, Queues.NonEeaDesk -> 1),
      Map(Queues.EGate -> 3, Queues.NonEeaDesk -> 3),
      5,
      Map(Queues.EGate -> 10, Queues.EeaDesk -> 15),
      0
    )

    val result = simulationParams.toQueryStringParams

    "Then I should see the processing times in the query string" >> {
      val expected = "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30"

      result must contain(expected)
    }

    "Then I should see the min desks in the query string" >> {
      val expected = "EGate_min=1&NonEeaDesk_min=1"

      result must contain(expected)
    }

    "Then I should see the max desks in the query string" >> {
      val expected = "EGate_max=3&NonEeaDesk_max=3"

      result must contain(expected)
    }

    "Then I should see the SLA in the query string" >> {
      val expected = "EGate_sla=10&EeaDesk_sla=15"

      result must contain(expected)
    }

    "The I should get a valid query string back" >> {

      result === "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSize=5&crunchOffsetMinutes=0&" +
        "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30&" +
        "EGate_min=1&NonEeaDesk_min=1&" +
        "EGate_max=3&NonEeaDesk_max=3&" +
        "EGate_sla=10&EeaDesk_sla=15"
    }

  }

  "When parsing a query string back into a simulations params object" >> {
    "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
      val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSize=5&crunchOffsetMinutes=0"
      val fakeRequest = FakeRequest("GET", s"/endpoint?$qs")
      val qsValues: Map[String, Seq[String]] = fakeRequest.queryString

      val expected = Success(SimulationParams(
        Terminal("T1"),
        LocalDate(2020, 2, 2),
        1.0,
        Map(),
        Map(),
        Map(),
        5,
        Map(),
        0
      ))

      val result = SimulationParams.fromQueryStringParams(qsValues)

      expected === result
    }
  }

  "Given a query string map containing all require fields then I should get back a successful SimulationParams" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSize=5&crunchOffsetMinutes=0&" +
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
      5,
      Map(Queues.EGate -> 10, Queues.EeaDesk -> 15),
      0
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    expected === result
  }

  "Given a query string map containing all invalid types then they should be ignored if they are not required" >> {
    val qs = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSize=5&crunchOffsetMinutes=0&" +
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
      5,
      Map(Queues.EGate -> 10),
      0
    ))

    val result = SimulationParams.fromQueryStringParams(qsValues)

    expected === result
  }

  "Missing required fields then I should get back a failure" >> {
    val qs = "date=2020-02-02&passengerWeighting=1.0&eGateBankSize=5&crunchOffsetMinutes=0&" +
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
