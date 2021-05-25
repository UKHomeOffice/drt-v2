package drt.shared

import controllers.ArrivalGenerator
import drt.shared.PaxTypes.{Transit, VisaNational}
import drt.shared.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import drt.shared.api.Arrival
import org.specs2.mutable.Specification

class PassengerNumberEstSpec extends Specification {

  "When estimating PCP Pax" >> {
    "Given an arrival with 100 total pax, no transfer and no API data" >> {
      "Then the PCP Pax should be 100" >> {

        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100)

        val result = flightWithSplits.pcpPaxEstimate

        result === 100
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and no API data" >> {
      "Then the PCP Pax Estimate should be 99" >> {

        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1)

        val result = flightWithSplits.pcpPaxEstimate

        result === 99
      }
    }

    "Given an arrival with 100 total pax, no transfer and 96 API passengers" >> {
      "Then the PCP Pax estimate should be 96" >> {

        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, splits = liveApiSplits(directPax = 96))

        val result = flightWithSplits.pcpPaxEstimate

        result === 96
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and 95 API passengers with 1 api Transfer" >> {
      "Then the PCP Pax estimate should be 95" >> {

        val splits = liveApiSplits(directPax = 95, transferPax = 1)
        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1, splits = splits)

        val result = flightWithSplits.pcpPaxEstimate

        result === 95
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and 50 API passengers (outside the acceptable threshold) " +
      "And the arrival has the LiveFeedSource" >> {
      "Then the PCP Pax estimate should be 99" >> {

        val splits = liveApiSplits(directPax = 50, transferPax = 1)
        val sources: Set[FeedSource] = Set(LiveFeedSource)
        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1, splits = splits, sources = sources)

        val result = flightWithSplits.pcpPaxEstimate

        result === 99
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and 50 API passengers (outside the acceptable threshold) " +
      "And the arrival has no live feed source" >> {
      "Then the PCP Pax estimate should be 50" >> {

        val splits = liveApiSplits(directPax = 50, transferPax = 1)
        val sources: Set[FeedSource] = Set()
        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1, splits = splits, sources = sources)

        val result = flightWithSplits.pcpPaxEstimate

        result === 50
      }
    }
  }


  "When calculating total pax" >> {
    "Given an arrival with 100 total pax, no transfer and no API data" >> {
      "Then the total pax be 100" >> {

        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100)

        val result = flightWithSplits.totalPax.get

        result === 100
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and no API data" >> {
      "Then the total pax should be 100" >> {

        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1)

        val result = flightWithSplits.totalPax.get

        result === 100
      }
    }

    "Given an arrival with 100 total pax, no transfer and 96 API passengers" >> {
      "Then the total pax should be 96" >> {

        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, splits = liveApiSplits(directPax = 96))

        val result = flightWithSplits.totalPax.get

        result === 96
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and 95 API passengers with 1 api Transfer" >> {
      "Then the total pax should be 96" >> {

        val splits = liveApiSplits(directPax = 95, transferPax = 1)
        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1, splits = splits)

        val result = flightWithSplits.totalPax.get

        result === 96
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and 50 API passengers (outside the acceptable threshold) " +
      "And the arrival has the LiveFeedSource" >> {
      "Then the total pax should be 100" >> {

        val splits = liveApiSplits(directPax = 50, transferPax = 1)
        val sources: Set[FeedSource] = Set(LiveFeedSource)
        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100,  transferPax = 1, splits = splits, sources = sources)

        val result = flightWithSplits.totalPax.get

        result === 100
      }
    }

    "Given an arrival with 100 total pax, 1 transfer and 50 API passengers with 1 API Transfer (outside the acceptable threshold) " +
      "And the arrival has no live feed source" >> {
      "Then the total pax should be 51" >> {

        val splits = liveApiSplits(directPax = 50, transferPax = 1)
        val sources: Set[FeedSource] = Set()
        val flightWithSplits: ApiFlightWithSplits = flightWithPaxAndApiSplits(actPax = 100, transferPax = 1, splits = splits, sources = sources)

        val result = flightWithSplits.totalPax.get

        result === 51
      }
    }
  }

  def flightWithPaxAndApiSplits(actPax: Int = 0,
                                transferPax: Int = 0,
                                splits: Set[Splits] = Set(),
                                sources: Set[FeedSource] = Set()
                               ): ApiFlightWithSplits = {
    val flight: Arrival = ArrivalGenerator.arrival(
      actPax = Option(actPax),
      tranPax = Option(transferPax),
      feedSources = sources
    )

    ApiFlightWithSplits(flight, splits)
  }

  def liveApiSplits(directPax: Int = 0, transferPax: Int = 0): Set[Splits] = Set(Splits(
    Set(
      ApiPaxTypeAndQueueCount(VisaNational, Queues.NonEeaDesk, directPax, None, None),
      ApiPaxTypeAndQueueCount(Transit, Queues.Transfer, transferPax, None, None),
    ),
    ApiSplitsWithHistoricalEGateAndFTPercentages,
    Option(EventTypes.DC)
  ))

}
