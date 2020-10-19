package actors.daily

import actors.ArrivalGenerator.flightWithSplitsForDayAndTerminal
import actors.GetState
import akka.actor.ActorRef
import akka.pattern.ask
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, T2, Terminal}
import drt.shared.{SDateLike, TQM, UtcDate}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}


class MockTerminalDayQueuesActor(day: SDateLike,
                                 terminal: Terminal,
                                 initialState: Map[TQM, CrunchMinute]) extends TerminalDayQueuesActor(day.getFullYear(), day.getMonth(), day.getDate(), terminal, () => day, None) {
  state = initialState
}

class TerminalDayFlightsActorSpec extends CrunchTestLike {
  val terminal: Terminal = T1
  val queue: Queue = EeaDesk

  val date: SDateLike = SDate("2020-01-01")
  val myNow: () => SDateLike = () => date

  "Given a terminal-day flight actor for a day which does not have any data" >> {

    "When I send a flight to persist which lies within the day, and then ask for its state I should see the flight" >> {
      val arrival = flightWithSplitsForDayAndTerminal(date)
      val flightsWithSplits = FlightsWithSplitsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === FlightsWithSplits(Map(arrival.unique-> arrival))
    }

    "When I send a flight which lies outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val arrival = flightWithSplitsForDayAndTerminal(otherDate)
      val flightsWithSplits = FlightsWithSplitsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === FlightsWithSplits.empty
    }

    "When I send flights to persist which lie both inside and outside the day, " +
      "and then ask for its state I should see only the flights inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = flightWithSplitsForDayAndTerminal(date)
      val outside = flightWithSplitsForDayAndTerminal(otherDate)
      val flightsWithSplits = FlightsWithSplitsDiff(List(inside, outside), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1 second)
      val expected = FlightsWithSplits(Map(inside.unique -> inside))

      result === expected
    }

    "When I send flights to persist for the right and wrong terminal " +
      "and then ask for its state I should see only the flights for the correct terminal" >> {

      val correctTerminal = flightWithSplitsForDayAndTerminal(date, T1)
      val wrongTerminal = flightWithSplitsForDayAndTerminal(date, T2)

      val flightsWithSplits = FlightsWithSplitsDiff(List(correctTerminal, wrongTerminal), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1 second)
      val expected = FlightsWithSplits(Map(correctTerminal.unique -> correctTerminal))

      result === expected
    }
  }

  private def sendFlightsToDay(flights: FlightsWithSplitsDiff,
                               actor: ActorRef): Future[FlightsWithSplits] = {
    actor.ask(flights).flatMap { _ =>
      actor.ask(GetState).mapTo[FlightsWithSplits]
    }
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: UtcDate): ActorRef = {
    system.actorOf(TerminalDayFlightActor.props(terminal, date, () => SDate(date)))
  }
}
