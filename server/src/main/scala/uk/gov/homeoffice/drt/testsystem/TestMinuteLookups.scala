package uk.gov.homeoffice.drt.testsystem

import actors.MinuteLookupsLike
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import drt.shared.CrunchApi.MillisSinceEpoch
import org.apache.pekko.actor.{ActorRef, ActorSystem, Props}
import org.apache.pekko.pattern.ask
import uk.gov.homeoffice.drt.models.CrunchMinute
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.testsystem.TestActors._
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, SDateLike, UtcDate}

import scala.concurrent.{ExecutionContext, Future}

case class TestMinuteLookups(system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             terminals: LocalDate => Seq[Terminal],
                             updateLiveView: (UtcDate, Iterable[CrunchMinute]) => Future[Unit],
                            )
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-minutes-lookup-kill-actor")

  private val resetQueuesData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(date.getFullYear, date.getMonth, date.getDate, terminal, now, Option(updateLiveView))))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  private val resetStaffData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayStaffActor(date.getFullYear, date.getMonth, date.getDate, terminal, now)))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  override val queueLoadsMinutesActor: ActorRef =
    system.actorOf(Props(new TestQueueLoadsMinutesActor(terminals, queuesLoadsLookup, updatePassengerMinutes, resetQueuesData)))

  override val queueMinutesRouterActor: ActorRef =
    system.actorOf(Props(new TestQueueMinutesRouterActor(terminals, queuesLookup, updateCrunchMinutes(updateLiveView), resetQueuesData)))

  override val staffMinutesRouterActor: ActorRef =
    system.actorOf(Props(new TestStaffMinutesRouterActor(terminals, staffLookup, updateStaffMinutes, resetStaffData)))
}
