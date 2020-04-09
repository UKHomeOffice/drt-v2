package actors

import actors.daily.{TerminalDayQueuesActor, TerminalDayStaffActor}
import actors.pointInTime.{CrunchStateReadActor, GetCrunchMinutes, GetStaffMinutes}
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.AskableActorRef
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM, TQM}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

case class MinuteLookups(system: ActorSystem, now: () => SDateLike, expireAfterMillis: Int, queuesByTerminal: Map[Terminal, Seq[Queue]])(implicit ec: ExecutionContext) {
  implicit val timeout: Timeout = new Timeout(5 seconds)

  def sendUpdateAndStopActor[A, B](container: MinutesContainer[A, B], askableActor: AskableActorRef): Future[MinutesContainer[A, B]] = {
    val eventualAck = askableActor.ask(container).mapTo[MinutesContainer[A, B]]
    eventualAck.onComplete(_ => askableActor.ask(PoisonPill))
    eventualAck
  }

  val updateCrunchMinutes: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] = (terminal: Terminal, date: SDateLike, container: MinutesContainer[CrunchMinute, TQM]) => {
    val askableActor: AskableActorRef = terminalDayQueueActor(terminal, date)
    sendUpdateAndStopActor(container, askableActor)
  }

  val updateStaffMinutes: (Terminal, SDateLike, MinutesContainer[StaffMinute, TM]) => Future[MinutesContainer[StaffMinute, TM]] = (terminal: Terminal, date: SDateLike, container: MinutesContainer[StaffMinute, TM]) => {
    val askableActor: AskableActorRef = terminalDayStaffActor(terminal, date)
    sendUpdateAndStopActor(container, askableActor)
  }

  def requestStateAndStopActor[A, B](askableActor: AskableActorRef, msg: Any): Future[Option[MinutesContainer[A, B]]] = {
    val eventualAck = askableActor.ask(msg).mapTo[Option[MinutesContainer[A, B]]]
    eventualAck.onComplete(_ => askableActor.ask(PoisonPill))
    eventualAck
  }

  val primaryCrunchLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[CrunchMinute, TQM]]] = (terminal: Terminal, date: SDateLike) => {
    val askableActor: AskableActorRef = terminalDayQueueActor(terminal, date)
    requestStateAndStopActor(askableActor, GetState)
  }

  def terminalDayQueueActor(terminal: Terminal, date: SDateLike): ActorRef =  {
    system.actorOf(Props(new TerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
  }

  val secondaryCrunchLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[CrunchMinute, TQM]]] = (terminal: Terminal, date: SDateLike) => {
    val askableActor: AskableActorRef = crunchStateReadActor(date)
    requestStateAndStopActor(askableActor, GetCrunchMinutes(terminal))
  }

  val primaryStaffLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[StaffMinute, TM]]] = (terminal: Terminal, date: SDateLike) => {
    val askableActor: AskableActorRef = terminalDayStaffActor(terminal, date)
    requestStateAndStopActor(askableActor, GetState)
  }

  def terminalDayStaffActor(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(Props(new TerminalDayStaffActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
  }

  val secondaryStaffLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[StaffMinute, TM]]] = (terminal: Terminal, date: SDateLike) => {
    val askableActor: AskableActorRef = crunchStateReadActor(date)
    requestStateAndStopActor(askableActor, GetStaffMinutes(terminal))
  }

  def crunchStateReadActor(date: SDateLike): ActorRef = {
    system.actorOf(Props(new CrunchStateReadActor(1000, date.addHours(4), expireAfterMillis, queuesByTerminal, date.millisSinceEpoch, date.addDays(1).millisSinceEpoch)))
  }

  def queueMinutesActor(clazz: Class[_]): ActorRef = system.actorOf(Props(clazz, now, queuesByTerminal.keys, primaryCrunchLookup, secondaryCrunchLookup, updateCrunchMinutes))

  def staffMinutesActor(clazz: Class[_]): ActorRef = system.actorOf(Props(clazz, now, queuesByTerminal.keys, primaryStaffLookup, secondaryStaffLookup, updateStaffMinutes))
}
