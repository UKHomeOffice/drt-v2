package drt.client.components

import drt.shared.splits.ApiSplitsToSplitRatio
import uk.gov.homeoffice.drt.arrivals.SplitStyle.{PaxNumbers, Percentage, Ratio}
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical, TerminalAverage}
import uk.gov.homeoffice.drt.ports.{ApiPaxTypeAndQueueCount, LiveFeedSource, PaxTypes, Queues}
import utest.{TestSuite, _}


object PaxSplitsDisplayTests extends TestSuite {

  import drt.shared.splits.ApiSplitsToSplitRatio._

  def tests = Tests {
    "When calculating the splits for each PaxType and Queue the the split should be applied as a ratio to flight pax" - {
      "Given 1 pax with a split of 1 EeaMachineReadable to Egate then I should get 1 Pax Split of 1 EeaMachineReadable to Egate" - {

        val splits = Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None)),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 1)

        val expected = Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None)),
          TerminalAverage,
          None,
          SplitStyle("Ratio")
        )

        assert(result == expected)
      }
      "Given 2 pax with a split of 1 EeaMachineReadable to Egate then I should get 1 Pax Split of 2 EeaMachineReadable to Egate" - {

        val splits = Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None)),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 2)

        val expected = Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 2, None, None)),
          TerminalAverage,
          None,
          SplitStyle("Ratio")
        )

        assert(result == expected)
      }
      "Given 2 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then I should get " +
        "pax splits of: 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk" - {

        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 2)

        val expected = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None,
          SplitStyle("Ratio")
        )

        assert(result == expected)
      }
      "Given 3 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then total split" +
        " pax should still add up to 3" - {

        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 3).splits.toList.map(_.paxCount).sum

        val expected = 3

        assert(result == expected)
      }
      "Given 3 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then total split" +
        " pax should still add up to 3" - {

        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 3).splits.toList.map(_.paxCount).sum

        val expected = 3

        assert(result == expected)
      }

      "Given 3 pax with a split of 1 EeaMachineReadable to Egate and 1 EeaMachineReadable to Desk then total split" +
        " all of the splits should contain whole numbers" - {

        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None
        )

        val ratioSplits = applyPaxSplitsToFlightPax(splits, 3)

        val rounded = ratioSplits.splits.toList.map(_.paxCount.toInt).sum
        val notRounded = ratioSplits.splits.toList.map(_.paxCount).sum.toInt

        assert(rounded == notRounded)
      }

      "Given a correction has been applied due to rounding, the correction should apply to the largest queue" - {

        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 10, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 12)

        val expected = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 11, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None,
          SplitStyle("Ratio")
        )

        assert(expected == result)
      }

      "Given a flight with all splits then I should get those splits applied as a ratio to the pax total" - {
        val pax = 152
        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11.399999999999999, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 0.6, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 36.85000000000001, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5.699999999999999, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30.150000000000006, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 15, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 0.3, None, None)), Historical, None, Percentage)

        val expected = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 17, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 56, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 9, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 46, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 23, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 0, None, None)), Historical, None, Ratio)

        val result = applyPaxSplitsToFlightPax(splits, pax)

        assert(result == expected)
      }

      "Given a a transfer split is included, then the split ratio should ignore the transfer queue" - {

        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 10, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.Transfer, 5, None, None)
          ),
          TerminalAverage,
          None
        )

        val result = applyPaxSplitsToFlightPax(splits, 12)

        val expected = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 11, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 1, None, None)
          ),
          TerminalAverage,
          None,
          SplitStyle("Ratio")
        )

        assert(expected == result)
      }

      "Given a flight with percentage splits, when I ask for pax per queue I should see the total pax broken down per queue" - {
        val flight = ArrivalGenerator
          .apiFlight(totalPax = Map(LiveFeedSource -> Passengers(Option(152), None)))
        val splits = Splits(
          Set(
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 11.399999999999999, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 0.6, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EGate, 36.85000000000001, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.NonEeaDesk, 5.699999999999999, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaMachineReadable, Queues.EeaDesk, 30.150000000000006, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.EeaNonMachineReadable, Queues.EeaDesk, 15, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.VisaNational, Queues.FastTrack, 0.3, None, None)), Historical, None, Percentage)

        val result = ApiSplitsToSplitRatio.paxPerQueueUsingBestSplitsAsRatio(ApiFlightWithSplits(flight, Set(splits)))

        val expected: Option[Map[Queues.Queue, Int]] = Option(Map(
          Queues.EeaDesk -> 69,
          Queues.EGate -> 56,
          Queues.NonEeaDesk -> 26,
          Queues.FastTrack -> 1
        ))

        assert(result == expected)
      }

      "Given a flight with PaxNumbers splits when I ask for pax per queue I should see the total broken down per queue" - {
        val flight = ArrivalGenerator
          .apiFlight(totalPax = Map(LiveFeedSource -> Passengers(Option(100), None)))
        val splits = Splits(Set(
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 15, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 5, None, None)),
          Historical, None, PaxNumbers)

        val result = ApiSplitsToSplitRatio.paxPerQueueUsingBestSplitsAsRatio(ApiFlightWithSplits(flight, Set(splits)))

        val expected: Option[Map[Queues.Queue, Int]] = Option(Map(
          Queues.NonEeaDesk -> 75,
          Queues.FastTrack -> 25
        ))

        assert(result == expected)
      }

      "Given a flight with no pax number for live feed and splits ApiSplitsWithHistoricalEGateAndFTPercentages I should see the total broken down per queue" - {
        val flight: Arrival = ArrivalGenerator
          .apiFlight(totalPax = Map(), feedSources = Set(LiveFeedSource))
          .copy(TotalPax = Map(LiveFeedSource -> Passengers(Some(100), None)))
        val splits = Splits(Set(
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 15, None, None),
          ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 5, None, None)),
          ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers)

        val apiFlightWithSplits = ApiFlightWithSplits(flight, Set(splits))
        val bestSplits = apiFlightWithSplits.bestSplits
        assert(bestSplits.contains(Splits(
          Set(ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.NonEeaDesk, 15, None, None),
            ApiPaxTypeAndQueueCount(PaxTypes.NonVisaNational, Queues.FastTrack, 5, None, None)),
          ApiSplitsWithHistoricalEGateAndFTPercentages, None, PaxNumbers))
        )
        val result = ApiSplitsToSplitRatio.paxPerQueueUsingBestSplitsAsRatio(apiFlightWithSplits)

        val expected: Option[Map[Queues.Queue, Int]] = Option(Map(
          Queues.NonEeaDesk -> 15,
          Queues.FastTrack -> 5
        ))
        assert(result == expected)
      }
    }
  }
}
