package test

import actors.FlightLookupsLike
import actors.daily.RequestAndTerminateActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import drt.shared.dates.UtcDate
import test.TestActors.{ResetData, TestFlightsRouterActor, TestTerminalDayFlightActor}

import scala.concurrent.Future

case class TestFlightLookups(system: ActorSystem,
                             now: () => SDateLike,
                             queuesByTerminal: Map[Terminal, Seq[Queue]]) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-flights-lookup-kill-actor")

  val resetFlightsData: (Terminal, UtcDate) => Future[Unit] = (terminal: Terminal, date: UtcDate) => {

    val actor = system.actorOf(Props(new TestTerminalDayFlightActor(date.year, date.month, date.day, terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  private val dummyProps: (SDateLike, Int) => Props = (_: SDateLike, _: Int) => Props()

  override val flightsActor: ActorRef = system.actorOf(
    Props(
      new TestFlightsRouterActor(
        queuesByTerminal.keys,
        flightsByDayLookup(None),
        updateFlights,
        resetFlightsData
      )))
}
