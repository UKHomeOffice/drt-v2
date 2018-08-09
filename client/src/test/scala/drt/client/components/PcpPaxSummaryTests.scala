package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch}
import drt.shared.Queues
import utest.{TestSuite, _}



object PcpPaxSummaryTests extends TestSuite {

  def tests = Tests {
    "Given a set of crunch minutes for one terminal" +
      "When I ask for a pax summary for a period of time " +
      "Then I should see total, eea & non-eea pax counts" - {
      val startMillis = SDate("2018-01-01T00:00").millisSinceEpoch
      val quantity = 10
      val crunchMinutes: Set[CrunchMinute] =
        generateCrunchMinutes(startMillis, quantity, "T1", Queues.EeaDesk, 1, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T1", Queues.NonEeaDesk, 0.5, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T1", Queues.EGate, 0.25, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T1", Queues.FastTrack, 0.1, 2)

      val summaryStartMillis = SDate("2018-01-01T00:04").millisSinceEpoch
      val summaryDurationMinutes = 3

      val result = PcpPaxSummary(summaryStartMillis, summaryDurationMinutes, crunchMinutes, "T1", Set(Queues.EeaDesk, Queues.NonEeaDesk))
      val expected = PcpPaxSummary(summaryStartMillis, summaryDurationMinutes, 3 * 1 + 3 * 0.5 + 3 * 0.25 + 3 * 0.1, Map(Queues.EeaDesk -> 3, Queues.NonEeaDesk -> 1.5))

      assert(result == expected)
    }

    "Given a set of crunch minutes for 2 terminals" +
      "When I ask for a pax summary for a period of time " +
      "Then I should see total, eea & non-eea pax counts for only the terminal specified" - {
      val startMillis = SDate("2018-01-01T00:00").millisSinceEpoch
      val quantity = 10
      val crunchMinutes: Set[CrunchMinute] =
        generateCrunchMinutes(startMillis, quantity, "T1", Queues.EeaDesk, 1, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T1", Queues.NonEeaDesk, 0.5, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T1", Queues.EGate, 0.25, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T1", Queues.FastTrack, 0.1, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T2", Queues.EeaDesk, 2, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T2", Queues.NonEeaDesk, 0.75, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T2", Queues.EGate, 0.2, 2) ++
          generateCrunchMinutes(startMillis, quantity, "T2", Queues.FastTrack, 0.05, 2)

      val summaryStartMillis = SDate("2018-01-01T00:04").millisSinceEpoch
      val summaryDurationMinutes = 3

      val result = PcpPaxSummary(summaryStartMillis, summaryDurationMinutes, crunchMinutes, "T1", Set(Queues.EeaDesk, Queues.NonEeaDesk))
      val expected = PcpPaxSummary(summaryStartMillis, summaryDurationMinutes, 3 * 1 + 3 * 0.5 + 3 * 0.25 + 3 * 0.1, Map(Queues.EeaDesk -> 3, Queues.NonEeaDesk -> 1.5))

      assert(result == expected)
    }
  }

  def generateCrunchMinutes(firstMinuteMillis: MillisSinceEpoch, quantity: Int, terminalName: String, queueName: String, pax: Double, work: Double): Set[CrunchMinute] = {
    (0 until quantity).map(offset => {
      CrunchMinute(terminalName, queueName, firstMinuteMillis + offset * 60000, pax, work, 0, 0)
    }).toSet
  }
}