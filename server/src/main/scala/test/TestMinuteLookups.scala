package test

import actors.MinuteLookupsLike
import actors.daily.RequestAndTerminateActor
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.SDateLike
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import services.SDate
import test.TestActors.{ResetData, TestQueueLoadsMinutesActor, TestQueueMinutesActor, TestStaffMinutesActor, TestTerminalDayQueueLoadsActor, TestTerminalDayQueuesActor, TestTerminalDayStaffActor}

import scala.concurrent.{ExecutionContext, Future}

case class TestMinuteLookups(system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             queuesByTerminal: Map[Terminal, Seq[Queue]])
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "test-minutes-lookup-kill-actor")

  val resetQueuesData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  val resetQueueLoadsData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayQueueLoadsActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  val resetStaffData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayStaffActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  override val queueLoadsMinutesActor: ActorRef = system.actorOf(Props(new TestQueueLoadsMinutesActor(queuesByTerminal.keys, queuesLoadsLookup, updatePassengerMinutes, resetQueuesData)))

  override val queueMinutesActor: ActorRef = system.actorOf(Props(new TestQueueMinutesActor(queuesByTerminal.keys, queuesLookup, updateCrunchMinutes, resetQueuesData)))

  override val staffMinutesActor: ActorRef = system.actorOf(Props(new TestStaffMinutesActor(queuesByTerminal.keys, staffLookup, updateStaffMinutes, resetStaffData)))
}
