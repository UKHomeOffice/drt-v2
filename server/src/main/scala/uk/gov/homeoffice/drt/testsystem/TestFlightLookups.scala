package uk.gov.homeoffice.drt.testsystem

import actors.FlightLookupsLike
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import TestActors.{ResetData, TestFlightsRouterActor, TestTerminalDayFlightActor}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.Future

case class TestFlightLookups(system: ActorSystem,
                             now: () => SDateLike,
                             queuesByTerminal: Map[Terminal, Seq[Queue]],
                             paxFeedSourceOrder: List[FeedSource],
                            ) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-flights-lookup-kill-actor")

  val resetFlightsData: (Terminal, UtcDate) => Future[Any] = (terminal: Terminal, date: UtcDate) => {
    val actor = system.actorOf(Props(new TestTerminalDayFlightActor(date.year, date.month, date.day, terminal, now, paxFeedSourceOrder)))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  override val flightsRouterActor: ActorRef = system.actorOf(
    Props(
      new TestFlightsRouterActor(
        queuesByTerminal.keys,
        flightsByDayLookup(None),
        updateFlights(None),
        resetFlightsData,
        paxFeedSourceOrder,
      )))
}
