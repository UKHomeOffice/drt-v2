package actors.daily

import actors.persistent.staffing.GetState
import akka.actor.ActorRef
import akka.pattern.ask
import controllers.ArrivalGenerator.arrivalForDayAndTerminal
import drt.shared.CrunchApi.CrunchMinute
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{ArrivalsDiff, TQM}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits
import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, T2, Terminal}
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

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
      val arrival = arrivalForDayAndTerminal(date)
      val flightsWithSplits = ArrivalsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 2.second)

      result === FlightsWithSplits(Map(arrival.unique -> ApiFlightWithSplits(arrival, Set(), lastUpdated = Option(myNow().millisSinceEpoch))))
    }

    "When I send a flight which lies outside the day, and then ask for its state I should see None" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val arrival = arrivalForDayAndTerminal(otherDate)
      val flightsWithSplits = ArrivalsDiff(List(arrival), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1.second)

      result === FlightsWithSplits.empty
    }

    "When I send flights to persist which lie both inside and outside the day, " +
      "and then ask for its state I should see only the flights inside the actor's day" >> {
      val otherDate = SDate("2020-01-02T00:00")
      val inside = arrivalForDayAndTerminal(date)
      val outside = arrivalForDayAndTerminal(otherDate)
      val flightsWithSplits = ArrivalsDiff(List(inside, outside), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1.second)
      val expected = FlightsWithSplits(Map(inside.unique -> ApiFlightWithSplits(inside, Set(), lastUpdated = Option(myNow().millisSinceEpoch))))

      result === expected
    }

    "When I send flights to persist for the right and wrong terminal " +
      "and then ask for its state I should see only the flights for the correct terminal" >> {

      val correctTerminal = arrivalForDayAndTerminal(date, T1)
      val wrongTerminal = arrivalForDayAndTerminal(date, T2)

      val flightsWithSplits = ArrivalsDiff(List(correctTerminal, wrongTerminal), List())

      val terminalDayActor: ActorRef = actorForTerminalAndDate(terminal, date.toUtcDate)

      val eventual = sendFlightsToDay(flightsWithSplits, terminalDayActor)
      val result = Await.result(eventual, 1.second)
      val expected = FlightsWithSplits(Map(correctTerminal.unique -> ApiFlightWithSplits(correctTerminal, Set(), lastUpdated = Option(myNow().millisSinceEpoch))))

      result === expected
    }
  }

  private def sendFlightsToDay(flights: ArrivalsDiff,
                               actor: ActorRef): Future[FlightsWithSplits] = {
    actor.ask(flights).flatMap { _ =>
      actor.ask(GetState).mapTo[FlightsWithSplits]
    }
  }

  private def actorForTerminalAndDate(terminal: Terminal, date: UtcDate): ActorRef = {
    system.actorOf(TerminalDayFlightActor.props(terminal, date, () => SDate(date)))
  }
}
