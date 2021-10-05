package drt.shared

import drt.shared.api.Arrival
import drt.shared.splits.ApiSplitsToSplitRatio
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.ports.PaxTypes.{Transit, VisaNational}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.{SplitSource, SplitSources}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, Historical}
import uk.gov.homeoffice.drt.ports._


class ApiFlightWithSplitsSpec extends Specification {
  "A flight with splits" should {
    "have valid Api when api splits pax count is within the 5% Threshold of LiveSourceFeed pax count" in {
      "and there are no transfer pax" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 41, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "and there are transfer pax only in the port feed data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 20, 21, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "and there are transfer pax only in the API data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 41, 20, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "and there are transfer pax both in the API data and in the port feed" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 20, 21, 20, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual true
        flightWithSplits.hasValidApi mustEqual true
      }

      "or there is a ScenarioSimulationSource" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(20), 0, 41, 0, Set(LiveFeedSource, ScenarioSimulationSource))

        flightWithSplits.hasValidApi mustEqual true
      }
    }

    "not have valid Api when api splits pax count outside the 5% Threshold of LiveSourceFeed pax count" in {
      "and there are no transfer pax" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 45, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }

      "and there are transfer pax only in the port feed data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 20, 24, 0, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }

      "and there are transfer pax only in the API data" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 21, 25, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(_.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }

      "and there are transfer pax both in the API data and in the port feed" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 20, 25, 25, Set(LiveFeedSource))
        val apiSplits = flightWithSplits.splits.find(s => s.source == SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages).get
        flightWithSplits.isWithinThreshold(apiSplits) mustEqual false
        flightWithSplits.hasValidApi mustEqual false
      }
    }

    "not have valid Api splits when the splits source type is not ApiSplitsWithHistoricalEGateAndFTPercentages" in {
      val flightWithSplits = flightWithPaxAndHistoricSplits(Option(40), 0, 41, 0, Set(LiveFeedSource))
      flightWithSplits.hasValidApi mustEqual false
    }

    "have valid Api splits when flight is has no LiveFeedSource" in {
      "and pax count differences are within the threshold" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 40, 0, Set())
        flightWithSplits.hasValidApi mustEqual true
      }
      "and pax count differences are outside the threshold" in {
        val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 100, 0, Set())
        flightWithSplits.hasValidApi mustEqual true
      }
    }
    
    "when there no actual pax number in liveFeed" in {
      "and api splits has pax number and hasValidApi is true" in {
        val flightWithSplits = flightWithPaxAndApiSplits(None, 0, 100, 0, Set(LiveFeedSource))
        flightWithSplits.hasValidApi mustEqual true
        flightWithSplits.pcpPaxEstimate mustEqual 100
        val paxPerQueue: Option[Map[Queues.Queue, Int]] = ApiSplitsToSplitRatio.paxPerQueueUsingBestSplitsAsRatio(flightWithSplits)
        paxPerQueue must beSome(collection.Map(Queues.NonEeaDesk -> 100))
      }
    }

    "give a pax count from splits when it has API splits" in {
      val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 45, 0, Set())
      flightWithSplits.totalPaxFromApiExcludingTransfer mustEqual Option(45)
    }

    "give a pax count from splits when it has API splits which does not include transfer pax" in {
      val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 45, 20, Set())
      flightWithSplits.totalPaxFromApiExcludingTransfer mustEqual Option(45)
    }

    "give a pax count from splits when it has API splits even when it is outside the trusted threshold" in {
      val flightWithSplits = flightWithPaxAndApiSplits(Option(40), 0, 150, 0, Set(LiveFeedSource))
      flightWithSplits.totalPaxFromApiExcludingTransfer mustEqual Option(150)
    }

    "give no pax count from splits it has no API splits" in {
      val flightWithSplits = flightWithPaxAndHistoricSplits(Option(40), 0, 45, 20, Set())
      flightWithSplits.totalPaxFromApiExcludingTransfer must beNone
    }

    "give None for totalPaxFromApiExcludingTransfer when it doesn't have live API splits" in {
      val fws = flightWithPaxAndHistoricSplits(Option(100), 10, 100, 0, Set())
      fws.totalPaxFromApiExcludingTransfer === None
    }

    "give None for totalPaxFromApi when it doesn't have live API splits" in {
      val fws = flightWithPaxAndHistoricSplits(Option(100), 10, 100, 0, Set())
      fws.totalPaxFromApi === None
    }
  }

  private def flightWithPaxAndApiSplits(actPax: Option[Int], transferPax: Int, splitsDirect: Int, splitsTransfer: Int, sources: Set[FeedSource]): ApiFlightWithSplits = {
    val flight: Arrival = ArrivalGenerator.arrival(actPax = actPax, tranPax = Option(transferPax), feedSources = sources)

    ApiFlightWithSplits(flight, Set(splitsForPax(directPax = splitsDirect, transferPax = splitsTransfer, ApiSplitsWithHistoricalEGateAndFTPercentages)))
  }

  private def flightWithPaxAndHistoricSplits(actPax: Option[Int], transferPax: Int, splitsDirect: Int, splitsTransfer: Int, sources: Set[FeedSource]): ApiFlightWithSplits = {
    val flight: Arrival = ArrivalGenerator.arrival(actPax = actPax, tranPax = Option(transferPax), feedSources = sources)

    ApiFlightWithSplits(flight, Set(splitsForPax(directPax = splitsDirect, transferPax = splitsTransfer, Historical)))
  }

  def splitsForPax(directPax: Int, transferPax: Int, source: SplitSource): Splits = Splits(Set(
    ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, directPax, None, None),
    ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, transferPax, None, None),
  ), source, Option(EventTypes.DC))

}
