package drt.shared

import controllers.ArrivalGenerator
import drt.shared.PaxTypes._
import drt.shared.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical}
import drt.shared.SplitRatiosNs.{SplitSource, SplitSources}
import drt.shared.api.Arrival
import org.specs2.mutable.Specification

class ApiFlightWithSplitsSpecs extends Specification {
  "flight Arrival" should {
    "have valid Api when api splits pax count is within 5% Threshold of LiveSourceFeed pax count" in {
      val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 41, 0, Set(LiveFeedSource))
      val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
      flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
      flightWithSplits.hasValidApi mustEqual true
    }

    "have valid Api when api splits pax count is within 5% Threshold of LiveSourceFeed pax count, after taking into account transfer pax" in {
      val flightWithSplits = flightWithPaxAndApiSplits(40, 20, 21, 0, Set(LiveFeedSource))
      val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
      flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
      flightWithSplits.hasValidApi mustEqual true
    }

    "have valid Api when api splits pax count is within 5% Threshold of LiveSourceFeed pax count, after taking into account transfer pax in both port feed and API" in {
      val flightWithSplits = flightWithPaxAndApiSplits(40, 20, 21, 20, Set(LiveFeedSource))
      val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
      flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
      flightWithSplits.hasValidApi mustEqual true
    }

    "not have valid Api when api splits pax count is not within 5% Threshold of LiveSourceFeed pax count" in {
      val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 50, 0, Set(LiveFeedSource))
      val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
      flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
      flightWithSplits.hasValidApi mustEqual false
    }

    "not have valid Api splits when source is not ApiSplitsWithHistoricalEGateAndFTPercentages" in {
      val flightWithSplits = flightWithPaxAndHistoricSplits(40, 0, 41, 0, Set(LiveFeedSource))
      flightWithSplits.hasValidApi mustEqual false
    }

    "have valid Api splits when flight is missing LiveFeedSource, even if above threshold" in {
      val flightWithSplits = flightWithPaxAndApiSplits(40, 0, 100, 0, Set())
      flightWithSplits.hasValidApi mustEqual true
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
