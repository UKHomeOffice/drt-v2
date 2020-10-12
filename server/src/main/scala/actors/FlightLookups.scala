package actors

import actors.PartitionedPortStateActor.GetFlightsForTerminalDateRange
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}
import actors.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import actors.queues.FlightsRouterActor
import actors.summaries.FlightsCacheActor
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import dispatch.Future
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
  implicit val timeout: Timeout = new Timeout(60 seconds)

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

  def cachedLookup(lookup: FlightsLookup, now: () => SDateLike)(implicit system: ActorSystem): FlightsLookup = (terminal: Terminal, date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {
    maybePit match {
      case None =>
        val cacheActor = system.actorOf(Props(new FlightsCacheActor(date, terminal, now)))
        cacheActor.ask(GetState).mapTo[Option[FlightsWithSplits]].flatMap {
          case None =>
            lookup(terminal, date, None).map { fws =>
              cacheActor ! fws
              fws
            }
          case Some(fws) => Future(fws)
        }
      case Some(pointInTime) => lookup(terminal, date, Option(pointInTime))
    }
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
