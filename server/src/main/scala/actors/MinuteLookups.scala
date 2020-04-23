package actors

import actors.daily.{TerminalDayQueuesActor, TerminalDayStaffActor}
import actors.pointInTime.{CrunchStateReadActor, GetCrunchMinutes, GetStaffMinutes}
import akka.actor.{ActorRef, ActorSystem, PoisonPill, Props}
import akka.pattern.ask
import akka.util.Timeout
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}
import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared.{SDateLike, TM, TQM}
import services.SDate
import test.TestActors.{ResetData, TestTerminalDayQueuesActor, TestTerminalDayStaffActor}

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps

trait MinuteLookupsLike {
  val system: ActorSystem
  implicit val ec: ExecutionContext
  val now: () => SDateLike
  val expireAfterMillis: Int
  val queuesByTerminal: Map[Terminal, Seq[Queue]]

  implicit val timeout: Timeout = new Timeout(5 seconds)

  def sendUpdateAndStopActor[A, B](container: MinutesContainer[A, B],
                                   actor: ActorRef): Future[MinutesContainer[A, B]] = {
    val eventualAck = actor.ask(container).mapTo[MinutesContainer[A, B]]
    eventualAck.onComplete(_ => actor.ask(PoisonPill))
    eventualAck
  }

  val updateCrunchMinutes: (Terminal, SDateLike, MinutesContainer[CrunchMinute, TQM]) => Future[MinutesContainer[CrunchMinute, TQM]] = (terminal: Terminal, date: SDateLike, container: MinutesContainer[CrunchMinute, TQM]) => {
    val actor: ActorRef = terminalDayQueueActor(terminal, date)
    sendUpdateAndStopActor(container, actor)
  }

  val updateStaffMinutes: (Terminal, SDateLike, MinutesContainer[StaffMinute, TM]) => Future[MinutesContainer[StaffMinute, TM]] = (terminal: Terminal, date: SDateLike, container: MinutesContainer[StaffMinute, TM]) => {
    val actor: ActorRef = terminalDayStaffActor(terminal, date)
    sendUpdateAndStopActor(container, actor)
  }

  def requestStateAndStopActor[A, B](actor: ActorRef,
                                     msg: Any): Future[Option[MinutesContainer[A, B]]] = {
    val eventualAck = actor.ask(msg).mapTo[Option[MinutesContainer[A, B]]]
    eventualAck.onComplete(_ => actor.ask(PoisonPill))
    eventualAck
  }

  val primaryCrunchLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[CrunchMinute, TQM]]] = (terminal: Terminal, date: SDateLike) => {
    val actor: ActorRef = terminalDayQueueActor(terminal, date)
    requestStateAndStopActor(actor, GetState)
  }

  def terminalDayQueueActor(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(Props(new TerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
  }

  val secondaryCrunchLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[CrunchMinute, TQM]]] = (terminal: Terminal, date: SDateLike) => {
    val actor: ActorRef = crunchStateReadActor(date)
    requestStateAndStopActor(actor, GetCrunchMinutes(terminal))
  }

  val primaryStaffLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[StaffMinute, TM]]] = (terminal: Terminal, date: SDateLike) => {
    val actor: ActorRef = terminalDayStaffActor(terminal, date)
    requestStateAndStopActor(actor, GetState)
  }

  def terminalDayStaffActor(terminal: Terminal, date: SDateLike): ActorRef = {
    system.actorOf(Props(new TerminalDayStaffActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
  }

  val secondaryStaffLookup: (Terminal, SDateLike) => Future[Option[MinutesContainer[StaffMinute, TM]]] = (terminal: Terminal, date: SDateLike) => {
    val actor: ActorRef = crunchStateReadActor(date)
    requestStateAndStopActor(actor, GetStaffMinutes(terminal))
  }

  def crunchStateReadActor(date: SDateLike): ActorRef = {
    system.actorOf(Props(new CrunchStateReadActor(1000, date.addHours(4), expireAfterMillis, queuesByTerminal, date.millisSinceEpoch, date.addDays(1).millisSinceEpoch)))
  }

  def queueMinutesActor(clazz: Class[_]): ActorRef

  def staffMinutesActor(clazz: Class[_]): ActorRef
}

case class MinuteLookups(system: ActorSystem,
                         now: () => SDateLike,
                         expireAfterMillis: Int,
                         queuesByTerminal: Map[Terminal, Seq[Queue]])
                        (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  override def queueMinutesActor(clazz: Class[_]): ActorRef = system.actorOf(Props(clazz, now, queuesByTerminal.keys, primaryCrunchLookup, secondaryCrunchLookup, updateCrunchMinutes))

  override def staffMinutesActor(clazz: Class[_]): ActorRef = system.actorOf(Props(clazz, now, queuesByTerminal.keys, primaryStaffLookup, secondaryStaffLookup, updateStaffMinutes))
}

case class TestMinuteLookups(system: ActorSystem,
                             now: () => SDateLike,
                             expireAfterMillis: Int,
                             queuesByTerminal: Map[Terminal, Seq[Queue]])
                            (implicit val ec: ExecutionContext) extends MinuteLookupsLike {
  val resetQueuesData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayQueuesActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  val resetStaffData: (Terminal, MillisSinceEpoch) => Future[Any] = (terminal: Terminal, millis: MillisSinceEpoch) => {
    val date = SDate(millis)
    val actor = system.actorOf(Props(new TestTerminalDayStaffActor(date.getFullYear(), date.getMonth(), date.getDate(), terminal, now)))
    actor.ask(ResetData).map(_ => actor ! PoisonPill)
  }

  override def queueMinutesActor(clazz: Class[_]): ActorRef = system.actorOf(Props(clazz, now, queuesByTerminal.keys, primaryCrunchLookup, secondaryCrunchLookup, updateCrunchMinutes, resetQueuesData))

  override def staffMinutesActor(clazz: Class[_]): ActorRef = system.actorOf(Props(clazz, now, queuesByTerminal.keys, primaryStaffLookup, secondaryStaffLookup, updateStaffMinutes, resetStaffData))
}
