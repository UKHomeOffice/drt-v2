package actors.daily

import controllers.ArrivalGenerator
import drt.shared.FlightUpdatesAndRemovals
import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.{ApiSplitsWithHistoricalEGateAndFTPercentages, TerminalAverage}
import uk.gov.homeoffice.drt.ports._

class FlightUpdatesAndRemovalsSpec extends Specification {
  private val update1000 = 1000L
  private val update1500 = 1500L
  private val update2000 = 2000L
  private val arrival1 = ArrivalGenerator.live(iata = "BA0001", totalPax = Option(10), schDt = "2022-05-01T00:25").toArrival(LiveFeedSource)
  private def splits(paxCount: Int, source: SplitSource): Set[Splits] = Set(Splits(
    splits = Set(ApiPaxTypeAndQueueCount(PaxTypes.GBRNational, Queues.EGate, paxCount, None, None)),
    source = source,
    maybeEventType = None,
    splitStyle = SplitStyle.Percentage,
  ))
  private val arrival2 = ArrivalGenerator.live(iata = "BA0002", totalPax = Option(10), schDt = "2022-05-01T12:40").toArrival(LiveFeedSource)
  private val arrival3 = ArrivalGenerator.live(iata = "BA0003", totalPax = Option(10), schDt = "2022-05-01T20:15").toArrival(LiveFeedSource)
  "Concerning FlightsWithSplitsDiffs" >> {
    "Given an empty FlightUpdatesAndRemovals" >> {
      "When I add an update it should retain it in its updates Map" >> {
        val updatesToAdd = FlightUpdatesAndRemovals(Map(1L -> ArrivalsDiff(Seq(arrival1), Seq())), Map(1L -> SplitsForArrivals(Map(arrival1.unique -> splits(10, TerminalAverage)))))
        val updatesAndRemovals = FlightUpdatesAndRemovals.empty ++ updatesToAdd
        updatesAndRemovals === updatesToAdd
      }
    }

    "Given a FlightUpdatesAndRemovals containing an arrival and a removal" >> {
      "When I add an ArrivalsDiff it should contain the updates and remove and record any removals" >> {
        val updatesAndRemovals = FlightUpdatesAndRemovals(Map(
          update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq(arrival3.unique))
        ), Map())
        val arrival1v2 = arrival1.copy(PassengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))
        val diff = ArrivalsDiff(Seq(arrival1v2), Seq(arrival2.unique))

        val expected = FlightUpdatesAndRemovals(Map(
          update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq(arrival3.unique)),
          update2000 -> ArrivalsDiff(Seq(arrival1v2), Seq(arrival2.unique))
        ), Map())

        updatesAndRemovals.add(diff, update2000) === expected
      }
    }

    "Given a FlightUpdatesAndRemovals containing arrivals and removals" >> {
      "When I ask for updates since 1500 it should return flights and removals with update times later than 1500" >> {
        val updatesAndRemovals = FlightUpdatesAndRemovals(Map(
          update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq(arrival1.unique)),
          update2000 -> ArrivalsDiff(Seq(arrival3), Seq(arrival2.unique))
        ), Map())
        updatesAndRemovals.updatesSince(update1500) === FlightUpdatesAndRemovals(Map(
          update2000 -> ArrivalsDiff(Seq(arrival3), Seq(arrival2.unique))
        ), Map())

      }
      "When I purge items older than 1500 it should return flights and removals with update times earlier than 1500" >> {
        val updatesAndRemovals = FlightUpdatesAndRemovals(Map(
          update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq()),
          update2000 -> ArrivalsDiff(Seq(arrival3), Seq())
        ), Map())
        val expected = FlightUpdatesAndRemovals(Map(
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq()),
          update2000 -> ArrivalsDiff(Seq(arrival3), Seq())
        ), Map())

        updatesAndRemovals.purgeOldUpdates(update1500) === expected
      }
    }
  }

  "Concerning ArrivalsDiff" >> {
    "Given a FlightUpdatesAndRemovals containing arrivals and a removal" >> {
      "When I apply a FlightsDiff it should contain the updates and remove and record any removals" >> {
        val updatesAndRemovals = FlightUpdatesAndRemovals(Map(
          update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq(arrival3.unique)),
        ), Map())
        val arrivalv2 = arrival1.copy(PassengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))
        val diff = ArrivalsDiff(Seq(arrivalv2), Seq(arrival2.unique))

        val expected = FlightUpdatesAndRemovals(Map(
          update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
          update1500 -> ArrivalsDiff(Seq(arrival2), Seq(arrival3.unique)),
          update2000 -> ArrivalsDiff(Seq(arrivalv2), Seq(arrival2.unique)),
        ), Map())

        updatesAndRemovals.add(diff, update2000) === expected
      }
    }
  }

  "Concerning SplitsForArrivals" >> {
    "Given a FlightUpdatesAndRemovals containing 2 arrivals" >> {
      "When I apply a SplitsForArrivals with an API split for the first arrival" >> {
        "Then I should get that updated flight when I ask for updates" >> {
          val updatesAndRemovals = FlightUpdatesAndRemovals(Map(
            update1000 -> ArrivalsDiff(Seq(arrival1), Seq()),
            update1500 -> ArrivalsDiff(Seq(arrival2), Seq(arrival3.unique)),
          ), Map())

          val apiSplits = splits(9, ApiSplitsWithHistoricalEGateAndFTPercentages)
          val diff = SplitsForArrivals(Map(arrival1.unique -> apiSplits))

          updatesAndRemovals.add(diff, update2000).updatesSince(update1500) === FlightUpdatesAndRemovals(Map(), Map(
            update2000 -> SplitsForArrivals(Map(arrival1.unique -> apiSplits))
          ))
        }
      }
    }
  }
}
