package actors

import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}
import actors.routing.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import actors.persistent.QueueLikeActor.UpdatedMillis
import actors.persistent.staffing.GetState
import actors.routing.FlightsRouterActor
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
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val now: () => SDateLike
  val requestAndTerminateActor: ActorRef

  val updateFlights: FlightsUpdate = (partition: (Terminal, UtcDate), diff: FlightUpdates) => {
    val (terminal, date) = partition
    val actor = system.actorOf(TerminalDayFlightActor.props(terminal, date, now))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, diff)).mapTo[UpdatedMillis]
  }

  def flightsByDayLookup(removalMessageCutOff: Option[FiniteDuration]): FlightsLookup =
    (maybePit: Option[MillisSinceEpoch]) => (date: UtcDate) => (terminal: Terminal) => {
      val props = (removalMessageCutOff, maybePit) match {
        case (None, None) => TerminalDayFlightActor.props(terminal, date, now)
        case (Some(finiteDuration), None) => TerminalDayFlightActor.propsWithRemovalsCutoff(terminal, date, now, finiteDuration)
        case (_, Some(pointInTime)) => TerminalDayFlightActor.propsPointInTime(terminal, date, now, pointInTime)
      }
      val actor = system.actorOf(props)
      requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[FlightsWithSplits]
    }

  def flightsActor: ActorRef

}

case class FlightLookups(system: ActorSystem,
                         now: () => SDateLike,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         updatesSubscriber: ActorRef,
                         removalMessageCutOff: Option[FiniteDuration] = None
                        ) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "flights-lookup-kill-actor")

  override val flightsActor: ActorRef = system.actorOf(
    Props(new FlightsRouterActor(
      updatesSubscriber,
      queuesByTerminal.keys,
      flightsByDayLookup(removalMessageCutOff),
      updateFlights
    ))
  )
}
