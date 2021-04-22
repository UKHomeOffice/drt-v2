package drt.shared

import controllers.ArrivalGenerator
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical}
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.api.Arrival
import org.specs2.mutable.Specification

class ApiFlightWithSplitsSpecs extends Specification {
  "flight Arrival" should {
    "have valid Api when api splits pax count is within the 5% Threshold of LiveSourceFeed pax count" in {
      "and there are no transfer pax" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 41, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "and there are transfer pax only in the port feed data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 20, 21, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "and there are transfer pax only in the API data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 41, 20, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "and there are transfer pax both in the API data and in the port feed" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 20, 21, 20, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }
    }

    "not have valid Api when api splits pax count outside the 5% Threshold of LiveSourceFeed pax count" in {
      "and there are no transfer pax" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 45, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }

      "and there are transfer pax only in the port feed data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 20, 24, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }

      "and there are transfer pax only in the API data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 21, 25, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }

      "and there are transfer pax both in the API data and in the port feed" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 20, 25, 25, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }
    }

    "not have valid Api splits when the splits source type is not ApiSplitsWithHistoricalEGateAndFTPercentages" in {
      val flightWithSplits = flightWithPaxAndHistoricSplits(40, 0, 41, 0, Set(LiveFeedSource))
      flightWithSplits.hasValidApi mustEqual false
    }

    "have valid Api splits when flight is has no LiveFeedSource" in {
      "and pax count differences are within the threshold" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 40, 0, Set())
        flightWithSplits.hasValidApi mustEqual true
      }
      "and pax count differences are outside the threshold" in {
        val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 100, 0, Set())
        flightWithSplits.hasValidApi mustEqual true
      }
    }
  }

  private def flightWithPaxAndApiSplits(actPax: Int, transferPax: Int, splitsDirect: Int, splitsTransfer: Int, sources: Set[FeedSource]): ApiFlightWithSplits = {
    val flight: Arrival = ArrivalGenerator.arrival(actPax = Option(actPax), tranPax = Option(transferPax), feedSources = sources)

    ApiFlightWithSplits(flight, Set(splitsForPax(directPax = splitsDirect, transferPax = splitsTransfer, ApiSplitsWithHistoricalEGateAndFTPercentages)))
  }

  private def flightWithPaxAndHistoricSplits(actPax: Int, transferPax: Int, splitsDirect: Int, splitsTransfer: Int, sources: Set[FeedSource]): ApiFlightWithSplits = {
    val flight: Arrival = ArrivalGenerator.arrival(actPax = Option(actPax), tranPax = Option(transferPax), feedSources = sources)

    ApiFlightWithSplits(flight, Set(splitsForPax(directPax = splitsDirect, transferPax = splitsTransfer, Historical)))
  }

  def splitsForPax(directPax: Int, transferPax: Int, source: SplitSource): Splits = Splits(Set(
    ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, directPax, None, None),
    ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, transferPax, None, None),
  ), source, Option(EventTypes.DC))

}
