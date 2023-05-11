package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import utest.{TestSuite, _}


object PcpPaxSummaryTests extends TestSuite {

  def tests = Tests {
    "Given a set of crunch minutes for one terminal" +
    "When I ask for a pax summary for a period of time " +
      "Then I should see total, eea & non-eea pax counts" - {
      val startMillis = SDate("2018-01-01T00:00").millisSinceEpoch
      val quantity = 10
      val crunchMinutesSet =
        generateCrunchMinutes(startMillis, quantity, T1, Queues.EeaDesk, 1, 2) ++
          generateCrunchMinutes(startMillis, quantity, T1, Queues.NonEeaDesk, 0.5, 2) ++
          generateCrunchMinutes(startMillis, quantity, T1, Queues.EGate, 0.25, 2) ++
          generateCrunchMinutes(startMillis, quantity, T1, Queues.FastTrack, 0.1, 2)

      val summaryStart = SDate("2018-01-01T00:04")
      val summaryDurationMinutes = 3

      val result = PcpPaxSummary(summaryStart, summaryDurationMinutes, crunchMinutesSet.toSeq, Set(Queues.EeaDesk, Queues.NonEeaDesk))
      val totalPax = (3 * 1 + 3 * 0.5 + 3 * 0.25 + 3 * 0.1).toInt
      val expected = PcpPaxSummary(totalPax, Map(Queues.EeaDesk -> 3, Queues.NonEeaDesk -> 1.5))

      assert(result == expected)
    }
  }

  def generateCrunchMinutes(firstMinuteMillis: MillisSinceEpoch, quantity: Int, terminal: Terminal, queue: Queue, pax: Double, work: Double): Set[CrunchMinute] = {
    (0 until quantity).map(offset => {
      CrunchMinute(terminal, queue, firstMinuteMillis + offset * 60000, pax, work, 0, 0, None)
    }).toSet
  }
}
