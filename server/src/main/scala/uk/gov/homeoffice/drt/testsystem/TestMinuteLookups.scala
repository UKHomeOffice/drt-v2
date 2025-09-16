package uk.gov.homeoffice.drt.testsystem

import actors.MinuteLookupsLike
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

case class TestMinuteLookups(system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             terminalsForDateRange: (LocalDate, LocalDate) => Seq[Terminal],
                             queuesForDateAndTerminal: (LocalDate, Terminal) => Seq[Queue],
                             updateLiveView: Terminal => (UtcDate, Iterable[CrunchMinute]) => Future[Unit],
                            )
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-minutes-lookup-kill-actor")

  private val resetQueuesData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val onUpdates: (UtcDate, Iterable[CrunchMinute]) => Future[Unit] =
      (date, state) => updateLiveView(terminal)(date, state)
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(SDate(millis).toUtcDate, terminal, queuesForDateAndTerminal, now, Option(onUpdates))))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  private val resetStaffData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val actor = system.actorOf(Props(new TestTerminalDayStaffActor(SDate(millis).toUtcDate, terminal, now)))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  override val queueLoadsMinutesActor: ActorRef =
    system.actorOf(Props(new TestQueueLoadsMinutesActor(terminalsForDateRange, queuesLoadsLookup(queuesForDateAndTerminal), updatePassengerMinutes(queuesForDateAndTerminal), resetQueuesData)))

  override val queueMinutesRouterActor: ActorRef =
    system.actorOf(Props(new TestQueueMinutesRouterActor(terminalsForDateRange, queuesLookup(queuesForDateAndTerminal), updateCrunchMinutes(updateLiveView, queuesForDateAndTerminal), resetQueuesData)))

  override val staffMinutesRouterActor: ActorRef =
    system.actorOf(Props(new TestStaffMinutesRouterActor(terminalsForDateRange, staffLookup, updateStaffMinutes, resetStaffData)))
}
