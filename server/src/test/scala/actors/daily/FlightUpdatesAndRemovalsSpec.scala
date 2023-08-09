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
  private val arrival1 = ArrivalGenerator.arrival(iata = "BA0001", passengerSources = Map(LiveFeedSource -> Passengers(Option(10), None)), schDt = "2022-05-01T00:25")
  private def splits(paxCount: Int, source: SplitSource): Set[Splits] = Set(Splits(
    splits = Set(ApiPaxTypeAndQueueCount(PaxTypes.GBRNational, Queues.EGate, paxCount, None, None)),
    source = source,
    maybeEventType = None,
    splitStyle = SplitStyle.Percentage,
  ))
  private val fws1 = ApiFlightWithSplits(arrival1, splits(10, TerminalAverage), lastUpdated = Option(update1000))
  private val arrival2 = ArrivalGenerator.arrival(iata = "BA0002", passengerSources = Map(LiveFeedSource -> Passengers(Option(10), None)), schDt = "2022-05-01T12:40")
  private val fws2 = ApiFlightWithSplits(arrival2, splits(10, TerminalAverage), lastUpdated = Option(update1500))
  private val arrival3 = ArrivalGenerator.arrival(iata = "BA0003", passengerSources = Map(LiveFeedSource -> Passengers(Option(10), None)), schDt = "2022-05-01T20:15")
  private val fws3 = ApiFlightWithSplits(arrival3, splits(10, TerminalAverage), lastUpdated = Option(update2000))
  "Concerning FlightsWithSplitsDiffs" >> {
    "Given an empty FlightUpdatesAndRemovals" >> {
      "When I add an update it should retain it in its updates Map" >> {
        val updatesToAdd = FlightUpdatesAndRemovals(Map(1L -> ArrivalsDiff(Seq(arrival1), Seq())), Map(1L -> SplitsForArrivals(Map(arrival1.unique -> splits(10, TerminalAverage)))))
        val updatesAndRemovals = FlightUpdatesAndRemovals.empty ++ updatesToAdd
        updatesAndRemovals === updatesToAdd
      }
    }

//    "Given a FlightUpdatesAndRemovals containing an arrival and a removal" >> {
//      "When I apply a FlightsWithSplitsDiff it should contain the updates and remove and record any removals" >> {
//        val removals = Seq((update1500, fws3.unique))
//        val updatesAndRemovals = FlightUpdatesAndRemovals(Seq(fws1, fws2), removals)
//        val fws1v2 = fws1.copy(arrival1.copy(PassengerSources = Map(ApiFeedSource -> Passengers(Option(100), None))))
//        val diff = FlightsWithSplitsDiff(Seq(fws1v2), Seq(fws2.unique))
//
//        val expected = FlightUpdatesAndRemovals(Seq(fws1v2), removals ++ Seq((update2000, fws2.unique)))
//
//        updatesAndRemovals.apply(diff, update2000) === expected
//      }
//    }

//    "Given a FlightUpdatesAndRemovals containing arrivals and removals" >> {
//      "When I ask for updates since 1500 it should return flights and removals with update times later than 1500" >> {
//        val updatesAndRemovals = FlightUpdatesAndRemovals(Seq(fws1, fws2, fws3), Seq((update1500, fws1.unique), (update2000, fws2.unique)))
//        updatesAndRemovals.updatesSince(update1500) === FlightsWithSplitsDiff(Seq(fws3), Set(fws2.unique))
//      }
//      "When I purge items older than 1500 it should return flights and removals with update times earlier than 1500" >> {
//        val updatesAndRemovals = FlightUpdatesAndRemovals(
//          Seq(fws1, fws2, fws3),
//          Seq((update1000, fws1.unique), (update1500, fws2.unique), (update2000, fws3.unique)))
//        val expected = FlightUpdatesAndRemovals(
//          Seq(fws2, fws3),
//          Seq((update1500, fws2.unique), (update2000, fws3.unique)))
//
//        updatesAndRemovals.purgeOldUpdates(update1500) === expected
//      }
//    }
  }

//  "Concerning ArrivalsDiff" >> {
//    "Given a FlightUpdatesAndRemovals containing an arrival and a removal" >> {
//      "When I apply a FlightsDiff it should contain the updates and remove and record any removals" >> {
//        val removals = Seq((update1500, fws3.unique))
//        val updatesAndRemovals = FlightUpdatesAndRemovals(Seq(fws1, fws2), removals)
//        val arrivalv2 = arrival1.copy(PassengerSources = Map(ApiFeedSource -> Passengers(Option(100), None)))
//        val diff = ArrivalsDiff(Seq(arrivalv2), Seq(fws2.unique))
//
//        val expected = FlightUpdatesAndRemovals(Seq(fws1.copy(apiFlight = arrivalv2, lastUpdated = Option(update2000))), removals ++ Seq((update2000, fws2.unique)))
//
//        updatesAndRemovals.apply(diff, update2000) === expected
//      }
//    }
//  }

//  "Concerning SplitsForArrivals" >> {
//    "Given a FlightUpdatesAndRemovals containing 2 arrivals" >> {
//      "When I apply a SplitsForArrivals with an API split for the first arrival" >> {
//        "Then I should get that updated flight when I ask for updates" >> {
//          val removals = Seq((update1500, fws3.unique))
//          val updatesAndRemovals = FlightUpdatesAndRemovals(Seq(fws1, fws2), removals)
//          val apiSplits = splits(9, ApiSplitsWithHistoricalEGateAndFTPercentages)
//          val diff = SplitsForArrivals(Map(arrival1.unique -> apiSplits))
//
//          val updatedFws1 = fws1.copy(
//            apiFlight = arrival1.copy(
//              FeedSources = arrival1.FeedSources + ApiFeedSource,
//              PassengerSources = arrival1.PassengerSources + (ApiFeedSource -> Passengers(Option(9), Option(0))),
//            ),
//            splits = splits(10, TerminalAverage) ++ apiSplits,
//            lastUpdated = Option(update2000),
//          )
//          updatesAndRemovals.apply(diff, 2000L).updatesSince(update1500) === FlightsWithSplitsDiff(Seq(updatedFws1), Set())
//        }
//      }
//    }
//  }
}
