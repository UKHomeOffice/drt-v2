package actors

import actors.PartitionedPortStateActor.{DateRangeLike, GetFlightsForTerminalDateRange}
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}
import actors.minutes.MinutesActorLike.{FlightsInRangeLookup, FlightsLookup, FlightsUpdate}
import actors.queues.FlightsRouterActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.FlightsApi.{FlightsWithSplits, FlightsWithSplitsDiff}
import drt.shared.Queues.Queue
import drt.shared.{SDateLike, UtcDate}
import drt.shared.Terminals.Terminal
import services.SDate

import scala.concurrent.{ExecutionContext, Future}
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

  val flightsLookup: FlightsLookup = (terminal: Terminal, date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case None => TerminalDayFlightActor.props(terminal, date, now)
      case Some(pointInTime) => TerminalDayFlightActor.propsPointInTime(terminal, date, now, pointInTime)
    }
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[FlightsWithSplits]
  }

  def flightsInRangeLookup(legacyActorProps: (SDateLike, Int) => Props): FlightsInRangeLookup = (terminal: Terminal, start: UtcDate, end: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case Some(pointInTime) => legacyActorProps(SDate(pointInTime), DrtStaticParameters.expireAfterMillis)
      case None =>
        val pointInTime = SDate(`end`).getLocalNextMidnight.addHours(4).millisSinceEpoch
        legacyActorProps(SDate(pointInTime), DrtStaticParameters.expireAfterMillis)
    }
    val actor = system.actorOf(props)
    val request = GetFlightsForTerminalDateRange(SDate(start).millisSinceEpoch, SDate(`end`).millisSinceEpoch, terminal)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, request)).mapTo[FlightsWithSplits]
  }

  def flightsActor: ActorRef

}

case class FlightLookups(system: ActorSystem,
                         now: () => SDateLike,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         updatesSubscriber: ActorRef,
                         flightsByDayStorageSwitchoverDate: SDateLike,
                         tempLegacyActorProps: (SDateLike, Int) => Props
                        )(implicit val ec: ExecutionContext) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "flights-lookup-kill-actor")

  override val flightsActor: ActorRef = system.actorOf(
    Props(new FlightsRouterActor(updatesSubscriber, queuesByTerminal.keys, flightsLookup, flightsInRangeLookup(tempLegacyActorProps), updateFlights, flightsByDayStorageSwitchoverDate))
  )

}
