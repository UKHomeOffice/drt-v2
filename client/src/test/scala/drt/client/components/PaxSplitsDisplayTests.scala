package drt.client.components

import drt.shared._
import utest.{TestSuite, _}


object PaxSplitsDisplayTests extends TestSuite {

  import ApiSplitsToSplitRatio._

  def tests = TestSuite {
    "When calculating the splits for each PaxType and Queue the the split should be applied as a ratio to flight pax" - {
      "Given 1 pax with a split of 1 EeaMachineReadable to Egate then I should get 1 Pax Split of 1 EeaMachineReadable to Egate" - {

        val splits = ApiSplits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1)),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 1)

        val expected = ApiSplits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1)),
          "whatevs",
          None,
          SplitStyle("Ratio")
        )

        assert(result == expected)
      }
      "Given 2 pax with a split of 1 EeaMachineReadable to Egate then I should get 1 Pax Split of 2 EeaMachineReadable to Egate" - {

        val splits = ApiSplits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1)),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 2)

        val expected = ApiSplits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2)),
          "whatevs",
          None,
          SplitStyle("Ratio")
        )

        assert(result == expected)
      }
      "Given 2 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then I should get " +
        "pax splits of: 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk" - {

        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 2)

        val expected = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None,
          SplitStyle("Ratio")
        )

        assert(result == expected)
      }
      "Given 3 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then total split" +
        " pax should still add up to 3" - {

        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 3).splits.toList.map(_.paxCount).sum

        val expected = 3

        assert(result == expected)
      }
      "Given 3 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then total split" +
        " pax should still add up to 3" - {

        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 3).splits.toList.map(_.paxCount).sum

        val expected = 3

        assert(result == expected)
      }

      "Given 3 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then total split" +
        " all of the splits should contain whole numbers" - {

        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None
        )

        val ratioSplits = applyPaxSplitsToFlightPax(splits, 3)

        val rounded = ratioSplits.splits.toList.map(_.paxCount.toInt).sum
        val notRounded = ratioSplits.splits.toList.map(_.paxCount).sum.toInt

        assert(rounded == notRounded)
      }

      "Given a correction has been applied due to rounding, the correction should apply to the largest queue" - {

        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 10),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 12)

        val expected = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 11),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None,
          SplitStyle("Ratio")
        )

        assert(expected == result)
      }

      "Given a flight with all splits then I should get those splits applied as a ratio to the pax total" - {
        val pax = 152
        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, "nonEeaDesk", 11.399999999999999),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, "fastTrack", 0.6),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, "eGate", 36.85000000000001),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, "nonEeaDesk", 5.699999999999999),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, "eeaDesk", 30.150000000000006),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, "eeaDesk", 15),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, "fastTrack", 0.3)), "Historical", None, Percentage)

        val expected = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, "nonEeaDesk", 17),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, "fastTrack", 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, "eGate", 56),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, "nonEeaDesk", 9),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, "eeaDesk", 46),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, "eeaDesk", 23),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, "fastTrack", 0)), "Historical", None, Ratio)

        val result = applyPaxSplitsToFlightPax(splits, pax)

        assert(result == expected)
      }

      "Given a a transfer split is included, then the split ratio should ignore the transfer queue" - {

        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 10),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.Transfer, 5)
          ),
          "whatevs",
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 12)

        val expected = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 11),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1)
          ),
          "whatevs",
          None,
          SplitStyle("Ratio")
        )

        assert(expected == result)
      }

      "Given a flight with all splits when I ask for pax per queue I should see the total broken down per queue" - {
        val flight = ArrivalGenerator.apiFlight(1, actPax = 152)
        val splits = ApiSplits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, "nonEeaDesk", 11.399999999999999),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, "fastTrack", 0.6),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, "eGate", 36.85000000000001),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, "nonEeaDesk", 5.699999999999999),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, "eeaDesk", 30.150000000000006),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, "eeaDesk", 15),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, "fastTrack", 0.3)), "Historical", None, Percentage)

        val result = ApiSplitsToSplitRatio.paxPerQueueUsingBestSplitsAsRatio(ApiFlightWithSplits(flight, Set(splits)))

        val expected = Option(Map(
          Queues.EeaDesk -> 69,
          Queues.EGate -> 56,
          Queues.NonEeaDesk -> 26,
          Queues.FastTrack -> 1
        ))

        assert(result == expected)
      }
    }
  }
}
