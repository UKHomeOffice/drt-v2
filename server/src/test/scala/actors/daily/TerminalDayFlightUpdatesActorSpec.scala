package actors.daily

import actors.InMemoryStreamingJournal
import akka.actor.{PoisonPill, Props}
import akka.pattern.ask
import drt.shared.{ArrivalGenerator, FlightUpdatesAndRemovals}
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSource
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages
import uk.gov.homeoffice.drt.ports.Terminals.T1
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class TerminalDayFlightUpdatesActorSpec extends CrunchTestLike {

  object TimeControl {
    var now: SDateLike = SDate.now()
  }

  private def splits(paxCount: Int, source: SplitSource): Set[Splits] = Set(Splits(
    splits = Set(ApiPaxTypeAndQueueCount(PaxTypes.GBRNational, Queues.EGate, paxCount, None, None)),
    source = source,
    maybeEventType = None,
    splitStyle = SplitStyle.Percentage,
  ))

  "Given a TerminalDayFlightUpdatesActor" >> {
    "When I ask it for updates when nothing has been persisted it should give an empty diff" >> {
      val updatesActor = system.actorOf(Props(new TerminalDayFlightUpdatesActor(2023, 8, 8, T1, () => SDate.now(), InMemoryStreamingJournal)))

      val result1 = Await.result(updatesActor.ask(GetAllUpdatesSince(0L)), 1.second)
      updatesActor ! PoisonPill
      result1 === FlightUpdatesAndRemovals(Map(), Map())
    }
    "When I ask it for updates after an arrival has been persisted it should give those diffs" >> {
      val flightRoutesActor = system.actorOf(Props(
        new TerminalDayFlightActor(2023, 8, 8, T1, () => TimeControl.now, None, None, List(LiveFeedSource, ApiFeedSource))
      ))
      val updatesActor = system.actorOf(Props(new TerminalDayFlightUpdatesActor(2023, 8, 8, T1, () => TimeControl.now, InMemoryStreamingJournal)))
      val arrival = ArrivalGenerator.arrival(
        iata = "BA0001",
        sch = SDate("2023-08-08T12:00").millisSinceEpoch,
        origin = PortCode("JFK"),
        passengerSources = Map(UnknownFeedSource -> Passengers(None, None))
      )

      TimeControl.now = SDate(1000L)
      val eventual = flightRoutesActor.ask(ArrivalsDiff(List(arrival), Set()))
        .flatMap { _ =>
          Thread.sleep(500L)
          updatesActor.ask(GetAllUpdatesSince(999L))
        }

      val result1 = Await.result(eventual, 1.second)
      result1 === FlightUpdatesAndRemovals(Map(1000L -> ArrivalsDiff(Seq(arrival), Seq())), Map())
    }

    "When I ask it for updates after an arrival and splits have been persisted it should give those diffs" >> {
      val flightRoutesActor = system.actorOf(Props(
        new TerminalDayFlightActor(2023, 8, 8, T1, () => TimeControl.now, None, None, List(LiveFeedSource, ApiFeedSource))
      ))
      val updatesActor = system.actorOf(Props(new TerminalDayFlightUpdatesActor(2023, 8, 8, T1, () => TimeControl.now, InMemoryStreamingJournal)))
      val arrival = ArrivalGenerator.arrival(
        iata = "BA0001",
        sch = SDate("2023-08-08T12:00").millisSinceEpoch,
        origin = PortCode("JFK"),
        passengerSources = Map(UnknownFeedSource -> Passengers(None, None))
      )

      TimeControl.now = SDate(1000L)
      val newSplits = splits(10, ApiSplitsWithHistoricalEGateAndFTPercentages)
      val eventual = flightRoutesActor.ask(ArrivalsDiff(List(arrival), Set()))
        .flatMap { _ =>
          flightRoutesActor.ask(SplitsForArrivals(Map(arrival.unique -> newSplits)))
            .flatMap { _ =>
              Thread.sleep(500L)
              updatesActor.ask(GetAllUpdatesSince(999L))
            }
        }

      val result1 = Await.result(eventual, 1.second)

      result1 === FlightUpdatesAndRemovals(Map(1000L -> ArrivalsDiff(Seq(arrival), Seq())), Map(1000L -> SplitsForArrivals(Map(arrival.unique -> newSplits))))
    }
  }
}
