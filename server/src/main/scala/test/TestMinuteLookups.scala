package test

import actors.MinuteLookupsLike
import actors.daily.{RequestAndTerminate, RequestAndTerminateActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.MillisSinceEpoch
import test.TestActors._
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{SDate, SDateLike}

import scala.concurrent.{ExecutionContext, Future}

case class TestMinuteLookups(system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             queuesByTerminal: Map[Terminal, Seq[Queue]])
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-minutes-lookup-kill-actor")

  private val resetQueuesData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(date.getFullYear, date.getMonth, date.getDate, terminal, now)))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  private val resetStaffData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayStaffActor(date.getFullYear, date.getMonth, date.getDate, terminal, now)))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, ResetData))
  }

  override val queueLoadsMinutesActor: ActorRef = system.actorOf(Props(new TestQueueLoadsMinutesActor(queuesByTerminal.keys, queuesLoadsLookup, updatePassengerMinutes, resetQueuesData)))

  override val queueMinutesRouterActor: ActorRef = system.actorOf(Props(new TestQueueMinutesRouterActor(queuesByTerminal.keys, queuesLookup, updateCrunchMinutes, resetQueuesData)))

  override val staffMinutesRouterActor: ActorRef = system.actorOf(Props(new TestStaffMinutesRouterActor(queuesByTerminal.keys, staffLookup, updateStaffMinutes, resetStaffData)))
}
