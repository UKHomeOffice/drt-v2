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
    }
  }
}
