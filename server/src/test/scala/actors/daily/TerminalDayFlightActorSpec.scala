package actors.daily

import akka.actor.ActorRef
import akka.pattern.ask
import controllers.ArrivalGenerator.arrivalForDayAndTerminal
import drt.shared.FlightsApi.RemoveSplits
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals._
import uk.gov.homeoffice.drt.ports.LiveFeedSource
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class TerminalDayFlightActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queue = EeaDesk

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given a terminal-day flight actor for a day which does not have any data" >> {

    "When I send a flight to persist which lies within the day, and then ask for its state I should see the flight" >> {
      val arrival = arrivalForDayAndTerminal(date).toArrival(LiveFeedSource)
      val flightsWithSplits = ArrivalsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 2.second)

      result === FlightsWithSplits(Map(arrival.unique -> ApiFlightWithSplits(arrival, Set(), lastUpdated = Option(myNow().millisSinceEpoch))))
    }

    "When I send a flight which lies outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val arrival = arrivalForDayAndTerminal(otherDate).toArrival(LiveFeedSource)
      val flightsWithSplits = ArrivalsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === FlightsWithSplits.empty
    }

    "When I send flights to persist which lie both inside and outside the day, " +
      "and then ask for its state I should see only the flights inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = arrivalForDayAndTerminal(date).toArrival(LiveFeedSource)
      val outside = arrivalForDayAndTerminal(otherDate).toArrival(LiveFeedSource)
      val flightsWithSplits = ArrivalsDiff(List(inside, outside), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1.second)
      val expected = FlightsWithSplits(Map(inside.unique -> ApiFlightWithSplits(inside, Set(), lastUpdated = Option(myNow().millisSinceEpoch))))

      result === expected
    }

    "When I send flights to persist for the right and wrong terminal " +
      "and then ask for its state I should see only the flights for the correct terminal" >> {

      val correctTerminal = arrivalForDayAndTerminal(date, T1).toArrival(LiveFeedSource)
      val wrongTerminal = arrivalForDayAndTerminal(date, T2).toArrival(LiveFeedSource)

      val flightsWithSplits = ArrivalsDiff(List(correctTerminal, wrongTerminal), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1.second)
      val expected = FlightsWithSplits(Map(correctTerminal.unique -> ApiFlightWithSplits(correctTerminal, Set(), lastUpdated = Option(myNow().millisSinceEpoch))))

      result === expected
    }
  }

  "Given a TerminalDayFlightActor with a flight" >> {
    "When I send it a message to remove splits" >> {
      "I should see that the flight no longer has splits" >> {
        val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)
        val arrival = arrivalForDayAndTerminal(date, terminal).toArrival(LiveFeedSource)
        val splits = Set(Splits(Set(), Historical, None))
        val eventualFlights = terminalDayActor.ask(ArrivalsDiff(Seq(arrival), Seq())).flatMap { _ =>
          terminalDayActor.ask(SplitsForArrivals(Map(arrival.unique -> splits))).flatMap { _ =>
            terminalDayActor.ask(RemoveSplits).flatMap { _ =>
              terminalDayActor.ask(GetState).map {
                case FlightsWithSplits(flights) =>
                  flights
              }
            }
          }
        }

        val expectedWithNoSplits = ApiFlightWithSplits(arrival, Set(), lastUpdated = Option(myNow().millisSinceEpoch))

        Await.result(eventualFlights, 1.second).values.toSet === Set(expectedWithNoSplits)
      }
    }
  }

  private def sendFlightsToDay(flights: ArrivalsDiff,
                               actor: ActorRef): Future[FlightsWithSplits] = {
    actor.ask(flights).flatMap { _ =>
      actor.ask(GetState).mapTo[FlightsWithSplits]
    }
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: UtcDate): ActorRef = {
    system.actorOf(TerminalDayFlightActor.propsWithRemovalsCutoff(terminal, date, () => SDate(date), None, paxFeedSourceOrder, None, None, None))
  }
}
