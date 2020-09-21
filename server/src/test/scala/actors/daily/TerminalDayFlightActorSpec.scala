package actors.daily

import actors.ArrivalGenerator.flightWithSplitsForDay
import actors.{ArrivalGenerator, GetState}
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.{EeaDesk, Queue}
import drt.shared.Terminals.{T1, Terminal}
import drt.shared.{ApiFlightWithSplits, SDateLike, SimulationMinute, TQM}
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
    val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)


    "When I send a flight to persist which lies within the day, and then ask for its state I should see the flight" >> {
      val arrival = flightWithSplitsForDay(date)
      val flightsWithSplits = FlightsWithSplitsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === FlightsWithSplits(List((arrival.unique, arrival)).toMap)
    }

    "When I send a flight which lies outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val arrival = flightWithSplitsForDay(otherDate)
      val flightsWithSplits = FlightsWithSplitsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1 second)

      result === FlightsWithSplits(List())
    }
//
//    "When I send flights to persist which lie both inside and outside the day, and then ask for its state I should see only the flights inside the actor's day" >> {
//      val otherDate = SDate("2020-01-02T00:00")
//      val inside = flightWithSplitsForDay(date)
//      val outside = flightWithSplitsForDay(otherDate)
//      val crunchMinutes = MinutesContainer(Set(inside, outside))
//      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date)
//
//      val eventual = sendFlightsToDay(crunchMinutes, terminalDayActor)
//      val result = Await.result(eventual, 1 second)
//
//      result === Option(MinutesContainer(Set(inside.copy(lastUpdated = Option(date.millisSinceEpoch)))))
//    }
  }

  private def sendFlightsToDay(flights: FlightsWithSplitsDiff,
                               actor: ActorRef): Future[FlightsWithSplits] = {
    actor.ask(flights).flatMap { _ =>
      actor.ask(GetState).mapTo[FlightsWithSplits]
    }
  }


  private def actorForTerminalAndDate(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(TerminalDayFlightActor.props(terminal, date, () => date))
  }
}
