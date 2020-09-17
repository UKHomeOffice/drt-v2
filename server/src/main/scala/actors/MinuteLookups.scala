package actors

import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayQueuesActor, TerminalDayStaffActor}
import actors.minutes.MinutesActorLike.MinutesLookup
import actors.minutes.{QueueMinutesActor, StaffMinutesActor}
import akka.actor.{ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM, TQM}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait MinuteLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  implicit val timeout: Timeout = new Timeout(60 hours)

  val now: () => SDateLike
  val expireAfterMillis: Int
  val queuesByTerminal: Map[Terminal, Seq[Queue]]
  val replayMaxCrunchStateMessages: Int
  val requestAndTerminateActor: ActorRef

  val updateCrunchMinutes: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] = (terminal: Terminal, date: SDateLike, container: MinutesContainer[CrunchMinute, TQM]) => {
    val actor = system.actorOf(TerminalDayQueuesActor.props(terminal, date, now))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, container)).mapTo[MinutesContainer[CrunchMinute, TQM]]
  }

  val updateStaffMinutes: (Terminal, SDateLike, MinutesContainer[StaffMinute, TM]) => Future[MinutesContainer[StaffMinute, TM]] = (terminal: Terminal, date: SDateLike, container: MinutesContainer[StaffMinute, TM]) => {
    val actor = system.actorOf(TerminalDayStaffActor.props(terminal, date, now))
    requestAndTerminateActor.ask(RequestAndTerminate(actor, container)).mapTo[MinutesContainer[StaffMinute, TM]]
  }

  val queuesLookup: MinutesLookup[CrunchMinute, TQM] = (terminal: Terminal, date: SDateLike, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case None => TerminalDayQueuesActor.props(terminal, date, now)
      case Some(pointInTime) => TerminalDayQueuesActor.propsPointInTime(terminal, date, now, pointInTime)
    }
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[Option[MinutesContainer[CrunchMinute, TQM]]]
  }

  val staffLookup: MinutesLookup[StaffMinute, TM] = (terminal: Terminal, date: SDateLike, maybePit: Option[MillisSinceEpoch]) => {
    val props = maybePit match {
      case None => TerminalDayStaffActor.props(terminal, date, now)
      case Some(pointInTime) => TerminalDayStaffActor.propsPointInTime(terminal, date, now, pointInTime)
    }
    val actor = system.actorOf(props)
    requestAndTerminateActor.ask(RequestAndTerminate(actor, GetState)).mapTo[Option[MinutesContainer[StaffMinute, TM]]]
  }
  
  def queueMinutesActor: ActorRef

  def staffMinutesActor: ActorRef
}

case class MinuteLookups(system: ActorSystem,
                         now: () => SDateLike,
                         expireAfterMillis: Int,
                         queuesByTerminal: Map[Terminal, Seq[Queue]],
                         override val replayMaxCrunchStateMessages: Int)
                        (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "minutes-lookup-kill-actor")

  override val queueMinutesActor: ActorRef = system.actorOf(Props(new QueueMinutesActor(queuesByTerminal.keys, queuesLookup, updateCrunchMinutes)))

  override val staffMinutesActor: ActorRef = system.actorOf(Props(new StaffMinutesActor(queuesByTerminal.keys, staffLookup, updateStaffMinutes)))
}
