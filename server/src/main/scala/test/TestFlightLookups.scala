package test

import actors.FlightLookupsLike
import actors.daily.RequestAndTerminateActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Queues.Queue
import drt.shared.{SDateLike, UtcDate}
import drt.shared.Terminals.Terminal
import services.SDate
import test.TestActors.{ResetData, TestFlightsRouterActor, TestTerminalDayFlightActor}

import scala.concurrent.{ExecutionContext, Future}

case class TestFlightLookups(system: ActorSystem,
                             now: () => SDateLike,
                             queuesByTerminal: Map[Terminal, Seq[Queue]],
                             updatesSubscriber: ActorRef)
                            (implicit val ec: ExecutionContext) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-flights-lookup-kill-actor")

  val resetFlightsData: (Terminal, UtcDate) => Future[Unit] = (terminal: Terminal, date: UtcDate) => {

    val actor = system.actorOf(Props(new TestTerminalDayFlightActor(date.year, date.month, date.day, terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  override val flightsActor: ActorRef = system.actorOf(
    Props(
      new TestFlightsRouterActor(
        updatesSubscriber,
        queuesByTerminal.keys,
        flightsLookup,
        flightsInRangeLookup,
        updateFlights, resetFlightsData
      )))
}
