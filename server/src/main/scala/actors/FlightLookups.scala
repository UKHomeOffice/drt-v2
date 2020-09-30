package actors

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import actors.queues.FlightsRouterActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, UtcDate}
import services.SDate

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

trait FlightLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout = new Timeout(60 hours)

  val now: () => SDateLike
  val requestAndTerminateActor: ActorRef

  val updateFlights: FlightsUpdate = (terminal: Terminal, date: UtcDate, diff: FlightsWithSplitsDiff) => {
    val actor = system.actorOf(TerminalDayFlightActor.props(terminal, date, now))
    system.log.info(s"About to update $terminal $date with ${diff.flightsToUpdate.size} flights")
    requestAndTerminateActor.ask(RequestAndTerminate(actor, diff)).mapTo[Seq[MillisSinceEpoch]]
  }

  val flightsByDayLookup: FlightsLookup = (terminal: Terminal, date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case None => TerminalDayFlightActor.props(terminal, date, now)
      case Some(pointInTime) => TerminalDayFlightActor.propsPointInTime(terminal, date, now, pointInTime)
    }
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[FlightsWithSplits]
  }

  def flightsByDayLookupLegacy(legacyActorProps: (SDateLike, Int) => Props): FlightsLookup = (terminal: Terminal, date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case Some(pointInTime) => legacyActorProps(SDate(pointInTime), DrtStaticParameters.expireAfterMillis)
      case None =>
        val pointInTime = SDate(date).addDays(1).addHours(4).millisSinceEpoch
        legacyActorProps(SDate(pointInTime), DrtStaticParameters.expireAfterMillis)
    }
    val actor = system.actorOf(props)
    val startMillis = SDate(date).millisSinceEpoch
    val endMillis = SDate(date).addDays(1).addMinutes(-1).millisSinceEpoch
    val request = GetFlightsForTerminalDateRange(startMillis, endMillis, terminal)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, request)).mapTo[FlightsWithSplits]
  }

  def flightsActor: ActorRef

}

case class FlightLookups(system: ActorSystem,
                         now: () => SDateLike,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         updatesSubscriber: ActorRef,
                         flightsByDayStorageSwitchoverDate: SDateLike,
                         tempLegacy1ActorProps: (SDateLike, Int) => Props,
                         tempLegacy2ActorProps: (SDateLike, Int) => Props
                        )(implicit val ec: ExecutionContext) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "flights-lookup-kill-actor")

  override val flightsActor: ActorRef = system.actorOf(
    Props(new FlightsRouterActor(
      updatesSubscriber,
      queuesByTerminal.keys,
      flightsByDayLookup,
      flightsByDayLookupLegacy(tempLegacy1ActorProps),
      flightsByDayLookupLegacy(tempLegacy2ActorProps),
      updateFlights,
      flightsByDayStorageSwitchoverDate,
      flightsByDayStorageSwitchoverDate))
  )
}
