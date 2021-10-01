package drt.client.components.scenarios

import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.dates.LocalDate
import drt.shared.{PaxTypesAndQueues, Queues}
import utest.{TestSuite, _}

class SimulationFormFieldsSpec extends TestSuite {
  val tests: Tests = Tests {
    "Given I am converting simulation params into a query string with a minimal data set" - {
      val simulationParams = SimulationFormFields(
        Terminal("T1"),
        LocalDate(2020, 2, 2),
        Option(1.0),
        Map(),
        Map(),
        Map(),
        IndexedSeq.fill(5)(Option(5)),
        Map(),
        0,
        Seq(1)
      )

      val result = simulationParams.toQueryStringParams

      "Then I should see the correct terminal in the query string" - {
        val expected = "terminal=T1"

        assert(result.contains(expected))
      }

      "Then I should see the correct date in the query string" - {
        val expected = "date=2020-02-02"

        assert(result.contains(expected))
      }

      "Then I should see the correct passenger weighting in the query string" - {
        val expected = "passengerWeighting=1.0"

        assert(result.contains(expected))
      }

      "Then I should see the correct egate bank sizes in the query string" - {
        val expected = "eGateBankSizes=5,5,5,5,5"

        assert(result.contains(expected))
      }

      "Then I should see the correct crunch offset in the query string" - {
        val expected = "crunchOffsetMinutes=0"

        assert(result.contains(expected))
      }

      "Then I should not see an entry for minDesks" - {
        val expectedNot = "minDesks"

        assert(!result.contains(expectedNot))
      }

      "Then I should not see an entry for maxDesks" - {
        val expectedNot = "maxDesks"

        assert(!result.contains(expectedNot))
      }

      "Then I should not see an entry for processingTimes" - {
        val expectedNot = "processingTimes"

        assert(!result.contains(expectedNot))
      }

      "Then I should not see an entry for slaByQueue" - {
        val expectedNot = "slaByQueue"

        assert(!result.contains(expectedNot))
      }

      "Then I should get a valid query string back" - {
        val expected = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&eGateOpenHours=1"

        assert(result == expected)
      }
    }

    "Given I am converting simulation params into a query string with queue data" - {
      val simulationParams = SimulationFormFields(
        Terminal("T1"),
        LocalDate(2020, 2, 2),
        Option(1.0),
        Map(PaxTypesAndQueues.eeaMachineReadableToDesk -> Option(60), PaxTypesAndQueues.eeaMachineReadableToEGate -> Option(30)),
        Map(Queues.EGate -> Option(1), Queues.NonEeaDesk -> Option(1)),
        Map(Queues.EGate -> Option(3), Queues.NonEeaDesk -> Option(3)),
        IndexedSeq.fill(5)(Option(5)),
        Map(Queues.EGate -> Option(10), Queues.EeaDesk -> Option(15)),
        0,
        Seq(1, 2)
      )

      val result = simulationParams.toQueryStringParams

      "Then I should see the processing times in the query string" - {
        val expected = "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30"

        assert(result.contains(expected))
      }

      "Then I should see the min desks in the query string" - {
        val expected = "EGate_min=1&NonEeaDesk_min=1"

        assert(result.contains(expected))
      }

      "Then I should see the max desks in the query string" - {
        val expected = "EGate_max=3&NonEeaDesk_max=3"

        assert(result.contains(expected))
      }

      "Then I should see the SLA in the query string" - {
        val expected = "EGate_sla=10&EeaDesk_sla=15"

        assert(result.contains(expected))
      }

      "The I should get a valid query string back" - {
        val expected = "terminal=T1&date=2020-02-02&passengerWeighting=1.0&eGateBankSizes=5,5,5,5,5&crunchOffsetMinutes=0&" +
          "eGateOpenHours=1,2&" +
          "EeaMachineReadable_EeaDesk=60&EeaMachineReadable_EGate=30&" +
          "EGate_min=1&NonEeaDesk_min=1&" +
          "EGate_max=3&NonEeaDesk_max=3&" +
          "EGate_sla=10&EeaDesk_sla=15"
        assert(result == expected)
      }
    }
  }
}

