package actors

import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import actors.queues.FlightsRouterActor
import actors.queues.QueueLikeActor.UpdatedMillis
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.DataUpdates.FlightUpdates
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.Queues.Queue
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import drt.shared.dates.UtcDate

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

trait FlightLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val now: () => SDateLike
  val requestAndTerminateActor: ActorRef

  val updateFlights: FlightsUpdate = (partition: (Terminal, UtcDate), diff: FlightUpdates) => {
    val (terminal, date) = partition
    val actor = system.actorOf(TerminalDayFlightActor.props(terminal, date, now))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, diff)).mapTo[UpdatedMillis]
  }

  val flightsByDayLookup: FlightsLookup = (terminal: Terminal, date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case None => TerminalDayFlightActor.props(terminal, date, now)
      case Some(pointInTime) => TerminalDayFlightActor.propsPointInTime(terminal, date, now, pointInTime)
    }
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[FlightsWithSplits]
  }

  def flightsActor: ActorRef

}

case class FlightLookups(system: ActorSystem,
                         now: () => SDateLike,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         updatesSubscriber: ActorRef
                        )(implicit val ec: ExecutionContext) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "flights-lookup-kill-actor")

  override val flightsActor: ActorRef = system.actorOf(
    Props(new FlightsRouterActor(
      updatesSubscriber,
      queuesByTerminal.keys,
      flightsByDayLookup,
      updateFlights
    ))
  )
}
