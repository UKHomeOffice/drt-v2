package actors

import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}
import actors.routing.FlightsRouterActor
import actors.routing.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.actor.commands.TerminalUpdateRequest
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Splits}
import uk.gov.homeoffice.drt.ports.FeedSource
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.language.postfixOps

trait FlightLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext = system.dispatcher
  implicit val timeout: Timeout = new Timeout(60 seconds)

  val now: () => SDateLike
  val requestAndTerminateActor: ActorRef
  val paxFeedSourceOrder: List[FeedSource]
  val terminalSplits: Terminal => Option[Splits]

  def updateFlights(removalMessageCutOff: Option[FiniteDuration],
                    updateLiveView: Iterable[ApiFlightWithSplits] => Unit,
                   )
                   (requestHistoricSplitsActor: Option[ActorRef],
                    requestHistoricPaxActor: Option[ActorRef],
                   ): FlightsUpdate = (partition: (Terminal, UtcDate), diff: FlightUpdates) => {
    val (terminal, date) = partition
    val props = TerminalDayFlightActor.propsWithRemovalsCutoff(
      terminal, date, now, removalMessageCutOff, paxFeedSourceOrder, terminalSplits(terminal), requestHistoricSplitsActor, requestHistoricPaxActor, Option(updateLiveView))
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, diff)).mapTo[Set[TerminalUpdateRequest]]
  }

  def flightsByDayLookup(removalMessageCutOff: Option[FiniteDuration]): FlightsLookup =
    (maybePit: Option[MillisSinceEpoch]) => (date: UtcDate) => (terminal: Terminal) => {
      val props = maybePit match {
        case None => TerminalDayFlightActor.propsWithRemovalsCutoff(
          terminal, date, now, removalMessageCutOff, paxFeedSourceOrder, terminalSplits(terminal), None, None, None)
        case Some(pointInTime) => TerminalDayFlightActor.propsPointInTime(
          terminal, date, now, pointInTime, removalMessageCutOff, paxFeedSourceOrder, terminalSplits(terminal))
      }
      val actor = system.actorOf(props)
      requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[FlightsWithSplits]
    }

  def flightsRouterActor: ActorRef

}

case class FlightLookups(system: ActorSystem,
                         now: () => SDateLike,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         removalMessageCutOff: Option[FiniteDuration],
                         paxFeedSourceOrder: List[FeedSource],
                         terminalSplits: Terminal => Option[Splits],
                         updateLiveView: Iterable[ApiFlightWithSplits] => Unit,
) extends FlightLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "flights-lookup-kill-actor")

  override val flightsRouterActor: ActorRef = system.actorOf(
    Props(new FlightsRouterActor(
      allTerminals = queuesByTerminal.keys,
      flightsByDayLookup = flightsByDayLookup(removalMessageCutOff),
      updateFlights = updateFlights(removalMessageCutOff, updateLiveView),
      paxFeedSourceOrder = paxFeedSourceOrder
    ))
  )
}
